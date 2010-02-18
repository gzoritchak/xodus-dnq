package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.core.dataStructures.decorators.HashMapDecorator;
import com.jetbrains.teamsys.core.dataStructures.decorators.HashSetDecorator;
import com.jetbrains.teamsys.core.dataStructures.decorators.QueueDecorator;
import com.jetbrains.teamsys.core.dataStructures.hash.HashMap;
import com.jetbrains.teamsys.core.dataStructures.hash.HashSet;
import com.jetbrains.teamsys.core.dataStructures.NanoSet;
import com.jetbrains.teamsys.database.*;
import com.jetbrains.teamsys.database.exceptions.*;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.*;

/**
 * Saves in queue all changes made during transient session
 * TODO: implement more intelligent changes tracking for links and properties
 *
 * @author Vadim.Gurov
 */
final class TransientChangesTrackerImpl implements TransientChangesTracker {

  private static final Log log = LogFactory.getLog(TransientEntityStoreImpl.class);

  private TransientStoreSession session;

  private Queue<Runnable> changes = new QueueDecorator<Runnable>();
  private Queue<Runnable> deleteIndexes = new QueueDecorator<Runnable>();
  private Queue<Runnable> rollbackChanges = new QueueDecorator<Runnable>();
  private LinkedList<Runnable> deleted = null;

  private Set<TransientEntity> changedPersistentEntities = new HashSetDecorator<TransientEntity>();
  private Set<TransientEntity> changedEntities = new HashSetDecorator<TransientEntity>();

  private Map<TransientEntity, Map<String, LinkChange>> entityToChangedLinksDetailed = new HashMapDecorator<TransientEntity, Map<String, LinkChange>>();
  private Map<TransientEntity, Map<String, PropertyChange>> entityToChangedPropertiesDetailed = new HashMapDecorator<TransientEntity, Map<String, PropertyChange>>();
  private Map<TransientEntity, Map<Index, Runnable>> entityToIndexChanges = new HashMapDecorator<TransientEntity, Map<Index, Runnable>>();

  public TransientChangesTrackerImpl(TransientStoreSession session) {
    this.session = session;
  }

  public boolean areThereChanges() {
    return !(changes.isEmpty() && (deleted == null || deleted.isEmpty()));
  }

  @NotNull
  public Queue<Runnable> getChanges() {
    Queue<Runnable> res = new LinkedList<Runnable>(deleteIndexes);
    res.addAll(changes);
    if (deleted != null) {
      res.addAll(getDeleted());
    }
    return res;
  }

  @NotNull
  public Queue<Runnable> getRollbackChanges() {
    return rollbackChanges;
  }

  public void clear() {
    deleteIndexes.clear();
    changes.clear();
    if (deleted != null) {
      getDeleted().clear();
    }
    rollbackChanges.clear();
    changedPersistentEntities.clear();
    changedEntities.clear();
    entityToChangedLinksDetailed.clear();
    entityToChangedPropertiesDetailed.clear();
  }

  @NotNull
  public Set<TransientEntity> getChangedPersistentEntities() {
    return changedPersistentEntities;
  }

  @NotNull
  public Set<TransientEntity> getChangedEntities() {
    return changedEntities;
  }

  public Set<TransientEntityChange> getChangesDescription() {
    //TODO: optimization hint: do not rebuild set on every request - incrementaly build it 
    Set<TransientEntityChange> res = new HashSetDecorator<TransientEntityChange>();

    for (TransientEntity e : getChangedEntities()) {
      // do not notify about temp and RemovedNew entities
      if (e.isTemporary() || (e.isRemoved() && e.wasNew())) continue;
      
      res.add(new TransientEntityChange(e, getChangedPropertiesDetailed(e),
              getChangedLinksDetailed(e), decodeState(e)));
    }

    return res;
  }

  private EntityChangeType decodeState(TransientEntity e) {
    switch (((AbstractTransientEntity) e).getState()) {
      case New:
      case SavedNew:
        return EntityChangeType.ADD;

      case RemovedSaved:
      case RemovedNew:
        return EntityChangeType.REMOVE;

      case Saved:
        return EntityChangeType.UPDATE;

      default:
        throw new IllegalStateException("Can't decode change for state [" + ((AbstractTransientEntity) e).getState() + "]");
    }
  }


  public TransientEntityChange getChangeDescription(TransientEntity e) {
    if (!e.isRemoved()) {
      return new TransientEntityChange(e, getChangedPropertiesDetailed(e),
              getChangedLinksDetailed(e), e.isNew() ? EntityChangeType.ADD : EntityChangeType.UPDATE);
    } else {
      throw new EntityRemovedException(e);
    }
  }

  @Nullable
  public Map<String, LinkChange> getChangedLinksDetailed(@NotNull TransientEntity e) {
    return entityToChangedLinksDetailed.get(e);
  }

  @Nullable
  public Map<String, PropertyChange> getChangedPropertiesDetailed(@NotNull TransientEntity e) {
    return entityToChangedPropertiesDetailed.get(e);
  }

  private void linkChangedDetailed(TransientEntity e, String linkName, LinkChangeType changeType, Set<TransientEntity> addedEntities, Set<TransientEntity> removedEntities) {
    Map<String, LinkChange> linksDetailed = entityToChangedLinksDetailed.get(e);
    if (linksDetailed == null) {
      linksDetailed = new HashMap<String, LinkChange>();
      entityToChangedLinksDetailed.put(e, linksDetailed);
      linksDetailed.put(linkName, new LinkChange(linkName, changeType, addedEntities, removedEntities));
    } else {
      LinkChange lc = linksDetailed.get(linkName);
      if (lc != null) {
        lc.setChangeType(lc.getChangeType().getMerged(changeType));
        if (addedEntities != null) {
          lc.setAddedEntities(addedEntities);
        }
        if (removedEntities != null) {
          lc.setRemovedEntities(removedEntities);
        }
      } else {
        linksDetailed.put(linkName, new LinkChange(linkName, changeType, addedEntities, removedEntities));
      }
    }
  }

  private Runnable propertyChangedDetailed(TransientEntity e, String propertyName, Comparable origValue, PropertyChangeType changeType, Runnable change) {
    Map<String, PropertyChange> propertiesDetailed = entityToChangedPropertiesDetailed.get(e);
    if (propertiesDetailed == null) {
      propertiesDetailed = new HashMap<String, PropertyChange>();
      entityToChangedPropertiesDetailed.put(e, propertiesDetailed);
    }
    // get previous change if any
    PropertyChange propertyChange = propertiesDetailed.get(propertyName);
    Runnable prevChange = propertyChange == null ? null : ((PropertyChangeInternal)propertyChange).getChange();
    propertiesDetailed.put(propertyName, new PropertyChangeInternal(propertyName, origValue, changeType, change));

    return prevChange;
  }

  public void entityAdded(@NotNull final TransientEntity e) {
    assert e.isNew();
    entityChanged(e);

    offerChange(new Runnable() {
      public void run() {
        if (!e.isRemovedOrTemporary()) {
          assert e.isNew();
          log.debug("Add new entity: " + e);
          ((TransientEntityImpl) e).setPersistentEntity(session.getPersistentSession().newEntity(e.getType()));
          assert e.isSaved();
        }
      }
    });

    rollbackChanges.offer(new Runnable() {
      public void run() {
        // rollback only if entity was actually saved
        if (e.isSaved()) {
          log.debug("Rollback in-memory transient entity from saved state: " + e);
          ((TransientEntityImpl) e).clearPersistentEntity();
          assert e.isNew();
        }
      }
    });
  }

  public void linkAdded(@NotNull final TransientEntity source, @NotNull final String linkName, @NotNull final TransientEntity target, Set<TransientEntity> added) {
    entityChanged(source);
    linkChangedDetailed(source, linkName, LinkChangeType.ADD, added, null);

    offerChange(new Runnable() {
      public void run() {
        if (!source.isRemovedOrTemporary() && !target.isRemovedOrTemporary()) {
          log.debug("Add link: " + source + "-[" + linkName + "]-> " + target);
          source.getPersistentEntity().addLink(linkName, target.getPersistentEntity());
        }
      }
    });
  }

  private void entityChanged(TransientEntity source) {
    if (source.isSaved()) {
      changedPersistentEntities.add(source);
    }
    changedEntities.add(source);
  }

  public void linkSet(@NotNull final TransientEntity source, @NotNull final String linkName, @NotNull final TransientEntity target, final TransientEntity oldTarget) {
    entityChanged(source);
    // don't set old target if the original old target was already set for current linkName 
    Map<String, LinkChange> linkChangeMap = entityToChangedLinksDetailed.get(source);
    boolean dontSetOldTarget = oldTarget == null || (linkChangeMap != null && linkChangeMap.get(linkName) != null);
    linkChangedDetailed(source, linkName, LinkChangeType.SET,
            new NanoSet<TransientEntity>(target), dontSetOldTarget ? null : new NanoSet<TransientEntity>(oldTarget));

    offerChange(new Runnable() {
      public void run() {
        if (!source.isRemovedOrTemporary() && !target.isRemovedOrTemporary()) {
          log.debug("Set link: " + source + "-[" + linkName + "]-> " + target);
          source.getPersistentEntity().setLink(linkName, target.getPersistentEntity());
        }
      }
    });

    changeIndexes(source, linkName);
  }

  public void entityDeleted(@NotNull final TransientEntity e) {
    // delete may be rolledback, so it's reasonable to store deleted entities in a separate set and merge with usual on getChanges request
    // also this set of deleted entities should be rolled back on delete rollback
    entityChanged(e);

    final Runnable deleteUniqueProperties = new Runnable() {
      public void run() {
        if (!e.wasNew()) {
          if (log.isDebugEnabled()) {
            log.debug("Delete unique properties for entity: " + e);
          }

          deleteIndexKeys(e);
        }
      }
    };
    deleteIndexes.offer(deleteUniqueProperties);

    final Runnable deleteOutgoingLinks = new Runnable() {
      public void run() {
        if (!e.wasNew()) {
          if (log.isDebugEnabled()) {
            log.debug("Delete outgoing links for entity: " + e);
          }

          ((BerkeleyDbEntity) ((TransientEntityImpl) e).getPersistentEntityInternal()).deleteLinks();
        }
      }
    };

    /* Commented code below (PART I and PART II) helps to determine from where incorrect entity deletion
       (when some incoming links to deleted entity are left in database) was made. */

    // PART I
    /* final Throwable cause;
    try {
      throw new RuntimeException();
    } catch (Throwable t) {
      cause = t;
    } */

    final Runnable deleteEntity = new Runnable() {
      public void run() {
        // do not delete entity that was new in this session
        if (!e.wasNew()) {
          if (log.isDebugEnabled()) {
            log.debug("Delete entity: " + e);
          }

          // PART II
          /* Map<String, EntityId> incomingLinks = e.getIncomingLinks();
          for (String linkName: incomingLinks.keySet()) {
            EntityId id = incomingLinks.get(linkName);
            throw new IllegalStateException("Incoming link " + linkName + " from entity with id " + id + " to " + e.getType() + " with id " + e.getId(), cause);
          } */

          // delete entity
          e.deleteInternal();
        }
      }
    };

    // all delete links must go first
    getDeleted().addFirst(deleteOutgoingLinks);
    // all delete entities must go last
    getDeleted().addLast(deleteEntity);

    rollbackChanges.offer(new Runnable() {
      public void run() {
        if (e.isRemoved()) {
          // rollback entity state to New or Saved
          ((TransientEntityImpl) e).rollbackDelete();
        }
        // discard delete change
        deleteIndexes.remove(deleteUniqueProperties);
        getDeleted().remove(deleteEntity);
        getDeleted().remove(deleteOutgoingLinks);
      }
    });
  }

  public void linkDeleted(@NotNull final TransientEntity source, @NotNull final String linkName, @NotNull final TransientEntity target) {
    // target is not changed - it has new incomming link
    entityChanged(source);

    HashSet<TransientEntity> removed = null;
    if (target != null) {
      removed = new HashSet<TransientEntity>();
      removed.add(target);
    }
    linkChangedDetailed(source, linkName, LinkChangeType.REMOVE, null, removed);

    offerChange(new Runnable() {
      public void run() {
        // do not remove link if source or target removed and was new, or source or target is temporary
        if (!(((source.isRemoved() && source.wasNew()) || source.isTemporary()) || ((target.isRemoved() && target.wasNew()) || target.isTemporary()))) {
          log.debug("Delete link: " + source + "-[" + linkName + "]-> " + target);
          ((TransientEntityImpl) source).getPersistentEntityInternal().deleteLink(linkName, ((TransientEntityImpl) target).getPersistentEntityInternal());
        }
      }
    });
  }

  public void linksDeleted(@NotNull final TransientEntity source, @NotNull final String linkName, Set<TransientEntity> removed) {
    entityChanged(source);
    linkChangedDetailed(source, linkName, LinkChangeType.REMOVE, null, removed);

    offerChange(new Runnable() {
      public void run() {
        // remove link if source is not removed or source is removed and was not new
        if (!source.isRemovedOrTemporary() || (source.isRemoved() && !source.wasNew())) {
          log.debug("Delete links: " + source + "-[" + linkName + "]-> *");
          ((TransientEntityImpl) source).getPersistentEntityInternal().deleteLinks(linkName);
        }
      }
    });
    // do not change indexes - empty link from index must be coutch during constraints phase
  }

  public void propertyChanged(@NotNull final TransientEntity e,
                              @NotNull final String propertyName,
                              @Nullable final Comparable propertyOldValue,
                              @NotNull final Comparable propertyNewValue) {
    entityChanged(e);

    Runnable changeProperty = new Runnable() {
      public void run() {
        if (!e.isRemovedOrTemporary()) {
          log.debug("Set property: " + e + "." + propertyName + "=" + propertyNewValue);
          e.getPersistentEntity().setProperty(propertyName, propertyNewValue);
        }
      }
    };

    offerChange(changeProperty, propertyChangedDetailed(e, propertyName, propertyOldValue, PropertyChangeType.UPDATE, changeProperty));
    changeIndexes(e, propertyName);
  }

  public void propertyDeleted(@NotNull final TransientEntity e, @NotNull final String propertyName, @Nullable final Comparable propertyOldValue) {
    entityChanged(e);
    Runnable deleteProperty = new Runnable() {
      public void run() {
        if (!e.isRemovedOrTemporary()) {
          log.debug("Delete property: " + e + "." + propertyName);
          e.getPersistentEntity().deleteProperty(propertyName);
          deleteIndexKeys(e, propertyName);
        }
      }
    };
    offerChange(deleteProperty, propertyChangedDetailed(e, propertyName, propertyOldValue, PropertyChangeType.REMOVE, deleteProperty));
    // do not change indexes - empty link from index must be coutch during constraints phase
  }

  public void historyCleared(@NotNull final String entityType) {
    offerChange(new Runnable() {
      public void run() {
        log.debug("Clear history of entities of type [" + entityType + "]");
        session.getPersistentSession().clearHistory(entityType);
      }
    });
  }

  public void blobChanged(@NotNull final TransientEntity e,
                          @NotNull final String blobName,
                          @NotNull final File file) {
    entityChanged(e);
    Runnable blobChanged = new Runnable() {
      public void run() {
        if (!e.isRemovedOrTemporary()) {
          log.debug("Set blob property: " + e + "." + blobName + "=" + file);
          e.getPersistentEntity().setBlob(blobName, file);
        }
      }
    };
    offerChange(blobChanged, propertyChangedDetailed(e, blobName, null, PropertyChangeType.UPDATE, blobChanged));
  }

  public void blobChanged(@NotNull final TransientEntity e,
                          @NotNull final String blobName,
                          @NotNull final String newValue) {
    entityChanged(e);
    Runnable blobChanged = new Runnable() {
      public void run() {
        if (!e.isRemovedOrTemporary()) {
          log.debug("Set blob property: " + e + "." + blobName + "=" + newValue);
          e.getPersistentEntity().setBlobString(blobName, newValue);
        }
      }
    };
    offerChange(blobChanged, propertyChangedDetailed(e, blobName, null, PropertyChangeType.UPDATE, blobChanged));
  }

  public void blobDeleted(@NotNull final TransientEntity e, @NotNull final String blobName) {
    entityChanged(e);

    Runnable deleteBlob = new Runnable() {
      public void run() {
        if (!e.isRemovedOrTemporary()) {
          log.debug("Delete blob property: " + e + "." + blobName);
          e.getPersistentEntity().deleteBlob(blobName);
        }
      }
    };
    offerChange(deleteBlob, propertyChangedDetailed(e, blobName, null, PropertyChangeType.REMOVE, deleteBlob));
  }

  public void offerChange(@NotNull final Runnable change, @Nullable Runnable changeToRemove) {
    if (changeToRemove != null) {
      changes.remove(changeToRemove);
    }
    changes.offer(change);
  }

  public void offerChange(@NotNull final Runnable change) {
    offerChange(change, null);
  }

  public void dispose() {
    session = null;
  }

  private LinkedList<Runnable> getDeleted() {
    if (deleted == null) {
      deleted = new LinkedList<Runnable>();
    }
    return deleted;
  }

  private Set<Index> getMetadataIndexes(TransientEntity e) {
    EntityMetaData md = getEntityMetaData(e);
    return md == null ? null : md.getIndexes();
  }

  private Set<Index> getMetadataIndexes(TransientEntity e, String field) {
    EntityMetaData md = getEntityMetaData(e);
    return md == null ? null : md.getIndexes(field);
  }

  private void changeIndexes(final TransientEntity e, String propertyName) {
    if (TransientStoreUtil.isPostponeUniqueIndexes()) {
        return;
    }
    // update all indexes for this property
    Set<Index> indexes = getMetadataIndexes(e, propertyName);
    if (indexes != null) {
      for (final Index index: indexes) {
        offerIndexChange(e, index, new Runnable(){
          public void run() {
            try {
              if (!e.isRemoved()) {
                if (e.isSaved() && e.wasNew()) {
                  // create new index
                  getPersistentSession().insertUniqueKey(
                          index, getIndexFieldsFinalValues(e, index), ((TransientEntityImpl) e).getPersistentEntityInternal());
                } else if (e.isSaved() && !e.wasNew()) {
                  // update existing index
                  getPersistentSession().deleteUniqueKey(index, getIndexFieldsOriginalValues(e, index));
                  getPersistentSession().insertUniqueKey(
                          index, getIndexFieldsFinalValues(e, index), ((TransientEntityImpl) e).getPersistentEntityInternal());
                }
              }
            } catch (EntityStoreException ex) {
              throwIndexUniquenessViolationException(e, index);
            }
          }
        });
      }
    }
  }

  private void offerIndexChange(TransientEntity e, Index index, Runnable change) {
    // actual index change will be performed just after last change of property or link, that are part of particalar index
    Map<Index, Runnable> indexChanges = entityToIndexChanges.get(e);
    if (indexChanges == null) {
      indexChanges = new HashMap<Index, Runnable>();
      entityToIndexChanges.put(e, indexChanges);
    }

    Runnable prevChange = indexChanges.put(index, change);
    if (prevChange != null) {
      changes.remove(prevChange);
    }

    changes.add(change);
  }

  private EntityMetaData getEntityMetaData(TransientEntity e) {
    ModelMetaData mdd = ((TransientEntityStore) session.getStore()).getModelMetaData();
    return mdd == null ? null : mdd.getEntityMetaData(e.getType());
  }

  private void deleteIndexKeys(TransientEntity e) {
    if (TransientStoreUtil.isPostponeUniqueIndexes()) {
        return;
    }
    EntityMetaData emd = getEntityMetaData(e);
    if (emd != null) {
      for (Index index : emd.getIndexes()) {
        getPersistentSession().deleteUniqueKey(index, getIndexFieldsOriginalValues(e, index));
      }
    }
  }

  private void deleteIndexKeys(TransientEntity e, String name) {
    if (TransientStoreUtil.isPostponeUniqueIndexes()) {
        return;
    }
    EntityMetaData emd = getEntityMetaData(e);
    if (emd != null) {
      for (Index index : emd.getIndexes(name)) {
        getPersistentSession().deleteUniqueKey(index, getIndexFieldsOriginalValues(e, index));
      }
    }
  }

  private List<Comparable> getIndexFieldsOriginalValues(TransientEntity e, Index index) {
    List<Comparable> res = new ArrayList<Comparable>(index.getFields().size());
    for (IndexField f: index.getFields()) {
      if (f.isProperty()) {
        res.add(getOriginalPropertyValue(e, f.getName()));
      } else {
        res.add(getOriginalLinkValue(e, f.getName()));
      }
    }
    return res;
  }

  private List<Comparable> getIndexFieldsFinalValues(TransientEntity e, Index index) {
    List<Comparable> res = new ArrayList<Comparable>(index.getFields().size());
    for (IndexField f: index.getFields()) {
      if (f.isProperty()) {
        res.add(e.getProperty(f.getName()));
      } else {
        res.add(e.getLink(f.getName()));
      }
    }
    return res;
  }

  private Comparable getOriginalPropertyValue(TransientEntity e, String propertyName) {
    // get from saved changes, if not - from db
    Map<String, PropertyChange> propertiesDetailed = getChangedPropertiesDetailed(e);
    if (propertiesDetailed != null) {
      PropertyChange propertyChange = propertiesDetailed.get(propertyName);
      if (propertyChange != null) {
        return propertyChange.getOldValue();
      }
    }
    return ((TransientEntityImpl)e).getPersistentEntityInternal().getProperty(propertyName);
  }

  private Comparable getOriginalLinkValue(TransientEntity e, String linkName) {
/*
    // get from saved changes, if not - from db
    Map<String, LinkChange> linksDetailed = getChangedLinksDetailed(e);
    if (linksDetailed != null) {
      LinkChange change = linksDetailed.get(linkName);
      if (change != null) {
        int size = change.getAddedEntitiesSize();
        if (size > 1) {
          throw new IllegalStateException("Multiple or empty association can't be a part of index.");
        } if (size == 1) {
          return ((TransientEntityImpl)change.getAddedEntities().iterator().next()).getPersistentEntityInternal();
        }
      }
    }
*/
    return ((TransientEntityImpl)e).getPersistentEntityInternal().getLink(linkName);
  }

  private StoreSession getPersistentSession() {
    return session.getPersistentSession();
  }

  private void throwIndexUniquenessViolationException(TransientEntity e, Index index) {
    throw new ConstraintsValidationException(new UniqueIndexViolationException(e, index));
  }

}
