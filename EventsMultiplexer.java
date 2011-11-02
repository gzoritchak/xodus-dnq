package jetbrains.teamsys.dnq.runtime.events;

/*Generated by MPS */

import jetbrains.exodus.database.TransientStoreSessionListener;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import java.util.Map;
import java.util.Queue;
import jetbrains.exodus.core.dataStructures.hash.HashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.jetbrains.annotations.Nullable;
import java.util.Set;
import jetbrains.exodus.database.TransientEntityChange;
import jetbrains.exodus.database.TransientStoreSession;
import jetbrains.teamsys.dnq.runtime.util.DnqUtils;
import com.jetbrains.teamsys.dnq.database.TransientStoreUtil;
import jetbrains.exodus.database.Entity;
import jetbrains.mps.baseLanguage.closures.runtime._FunctionTypes;
import jetbrains.exodus.database.TransientEntity;
import java.util.concurrent.ConcurrentLinkedQueue;
import jetbrains.mps.internal.collections.runtime.QueueSequence;
import jetbrains.exodus.database.EntityMetaData;
import jetbrains.exodus.database.ModelMetaData;
import jetbrains.springframework.configuration.runtime.ServiceLocator;
import jetbrains.mps.internal.collections.runtime.Sequence;
import jetbrains.exodus.core.execution.Job;
import jetbrains.exodus.database.EntityStore;
import jetbrains.exodus.database.EntityId;

public class EventsMultiplexer implements TransientStoreSessionListener {
  protected static Log log = LogFactory.getLog(EventsMultiplexer.class);

  private Map<EventsMultiplexer.FullEntityId, Queue<IEntityListener>> instanceToListeners = new HashMap<EventsMultiplexer.FullEntityId, Queue<IEntityListener>>();
  private Map<String, Queue<IEntityListener>> typeToListeners = new HashMap<String, Queue<IEntityListener>>();
  private ReentrantReadWriteLock rwl = new ReentrantReadWriteLock();

  private EventsMultiplexer() {
  }

  public void flushed(@Nullable Set<TransientEntityChange> changes) {
    this.fire(Where.SYNC_AFTER_FLUSH, changes);
    this.asyncFire(changes);
  }

  public void commited(@Nullable Set<TransientEntityChange> changes) {
    {
      final TransientStoreSession superSession_9klgcu_a0b = DnqUtils.getCurrentTransientSession();
      if (superSession_9klgcu_a0b != null) {
        TransientStoreUtil.suspend(superSession_9klgcu_a0b);
      }
      try {
        final TransientStoreSession ts1_9klgcu_a0b = DnqUtils.beginTransientSession("commited_0", false);
        try {
          this.fire(Where.SYNC_AFTER_FLUSH, changes);
        } catch (Throwable _ex_) {
          TransientStoreUtil.abort(_ex_, ts1_9klgcu_a0b);
          throw new RuntimeException("Actual throws is inside about.");
        } finally {
          TransientStoreUtil.commit(ts1_9klgcu_a0b);
        }
      } finally {
        if (superSession_9klgcu_a0b != null) {
          DnqUtils.resumeTransientSession(superSession_9klgcu_a0b);
        }
      }
    }
    this.asyncFire(changes);
  }

  public void beforeFlush(@Nullable Set<TransientEntityChange> changes) {
    this.fire(Where.SYNC_BEFORE_CONSTRAINTS, changes);
  }

  public void beforeFlushAfterConstraintsCheck(@Nullable Set<TransientEntityChange> changes) {
    this.fire(Where.SYNC_BEFORE_FLUSH, changes);
  }

  private void asyncFire(final Set<TransientEntityChange> changes) {
    EventsMultiplexerJobProcessor.getInstance().queue(new EventsMultiplexer.JobImpl(this, changes));
  }

  private void fire(Where where, Set<TransientEntityChange> changes) {
    for (TransientEntityChange c : changes) {
      this.handlePerEntityChanges(where, c);
      this.handlePerEntityTypeChanges(where, c);
    }
  }

  public void addListener(final Entity e, final _FunctionTypes._void_P1_E0<? super Entity> l) {
    // for backward compatibility
    this.addListener(e, new EntityAdapter() {
      public void updatedSync(Entity old, Entity current) {
        l.invoke(e);
      }
    });
  }

  public void addListener(Entity e, IEntityListener listener) {
    // typecast to disable generator hook
    if ((Object) e == null || listener == null) {
      if (log.isWarnEnabled()) {
        log.warn("Can't add null listener to null entity");
      }
      return;
    }
    if (((TransientEntity) e).isNew()) {
      throw new IllegalStateException("Entity is not saved into database - you can't listern to it.");
    }
    final EventsMultiplexer.FullEntityId id = new EventsMultiplexer.FullEntityId(e.getStore(), e.getId());
    this.rwl.writeLock().lock();
    try {
      Queue<IEntityListener> listeners = this.instanceToListeners.get(id);
      if (listeners == null) {
        listeners = new ConcurrentLinkedQueue<IEntityListener>();
        this.instanceToListeners.put(id, listeners);
      }
      listeners.add(listener);
    } finally {
      this.rwl.writeLock().unlock();
    }
  }

  public void removeListener(Entity e, IEntityListener listener) {
    // typecast to disable generator hook
    if ((Object) e == null || listener == null) {
      if (log.isWarnEnabled()) {
        log.warn("Can't remove null listener from null entity");
      }
      return;
    }
    final EventsMultiplexer.FullEntityId id = new EventsMultiplexer.FullEntityId(e.getStore(), e.getId());
    this.rwl.writeLock().lock();
    try {
      final Queue<IEntityListener> listeners = this.instanceToListeners.get(id);
      if (listeners != null) {
        listeners.remove(listener);
        if (listeners.size() == 0) {
          this.instanceToListeners.remove(id);
        }
      }
    } finally {
      this.rwl.writeLock().unlock();
    }
  }

  public void addListener(String entityType, IEntityListener listener) {
    //  ensure that this code will be executed outside of transaction 
    this.rwl.writeLock().lock();
    try {
      Queue<IEntityListener> listeners = this.typeToListeners.get(entityType);
      if (listeners == null) {
        listeners = new ConcurrentLinkedQueue<IEntityListener>();
        this.typeToListeners.put(entityType, listeners);
      }
      listeners.add(listener);
    } finally {
      this.rwl.writeLock().unlock();
    }
  }

  public void removeListener(String entityType, IEntityListener listener) {
    this.rwl.writeLock().lock();
    try {
      Queue<IEntityListener> listeners = this.typeToListeners.get(entityType);
      if (listeners != null) {
        listeners.remove(listener);
        if (listeners.size() == 0) {
          this.typeToListeners.remove(entityType);
        }
      }
    } finally {
      this.rwl.writeLock().unlock();
    }
  }

  public void clearListeners() {
    this.rwl.writeLock().lock();
    try {
      this.typeToListeners.clear();
      for (final EventsMultiplexer.FullEntityId id : this.instanceToListeners.keySet()) {
        if (log.isWarnEnabled()) {
          log.warn(listenerToString(id, this.instanceToListeners.get(id)));
        }
      }
      instanceToListeners.clear();
    } finally {
      this.rwl.writeLock().unlock();
    }
  }

  public String listenerToString(final EventsMultiplexer.FullEntityId id, Queue<IEntityListener> listeners) {
    final StringBuilder builder = new StringBuilder(40);
    builder.append("Unregistered entity to listener class: ");
    id.toString(builder);
    builder.append(" ->");
    for (IEntityListener listener : QueueSequence.fromQueue(listeners)) {
      builder.append(' ');
      builder.append(listener.getClass().getName());
    }
    return builder.toString();
  }

  private void handlePerEntityChanges(Where where, TransientEntityChange c) {
    Queue<IEntityListener> listeners = null;
    final TransientEntity e = c.getTransientEntity();
    final EventsMultiplexer.FullEntityId id = new EventsMultiplexer.FullEntityId(e.getStore(), e.getId());
    this.rwl.readLock().lock();
    try {
      listeners = this.instanceToListeners.get(id);
    } finally {
      this.rwl.readLock().unlock();
    }
    this.handleChange(where, c, listeners);
  }

  private void handlePerEntityTypeChanges(Where where, TransientEntityChange c) {
    EntityMetaData emd = ((ModelMetaData) ServiceLocator.getBean("modelMetaData")).getEntityMetaData(c.getTransientEntity().getType());
    if (emd != null) {
      for (String type : Sequence.fromIterable(emd.getThisAndSuperTypes())) {
        Queue<IEntityListener> listeners = null;
        this.rwl.readLock().lock();
        try {
          listeners = this.typeToListeners.get(type);
        } finally {
          this.rwl.readLock().unlock();
        }
        this.handleChange(where, c, listeners);
      }
    }
  }

  private void handleChange(Where where, TransientEntityChange c, Queue<IEntityListener> listeners) {
    if (listeners != null) {
      for (IEntityListener l : listeners) {
        try {
          switch (where) {
            case SYNC_BEFORE_CONSTRAINTS:
              switch (c.getChangeType()) {
                case ADD:
                  l.addedSyncBeforeConstraints(c.getTransientEntity());
                  break;
                case UPDATE:
                  l.updatedSyncBeforeConstraints(TransientStoreUtil.readonlyCopy(c), c.getTransientEntity());
                  break;
                case REMOVE:
                  l.removedSyncBeforeConstraints(TransientStoreUtil.readonlyCopy(c));
                  break;
                default:
                  throw new IllegalArgumentException("Illegal arguments " + where + ":" + c.getChangeType());
              }
              break;
            case SYNC_BEFORE_FLUSH:
              switch (c.getChangeType()) {
                case ADD:
                  l.addedSyncBeforeFlush(c.getTransientEntity());
                  break;
                case UPDATE:
                  l.updatedSyncBeforeFlush(TransientStoreUtil.readonlyCopy(c), c.getTransientEntity());
                  break;
                case REMOVE:
                  l.removedSyncBeforeFlush(TransientStoreUtil.readonlyCopy(c));
                  break;
                default:
                  throw new IllegalArgumentException("Illegal arguments " + where + ":" + c.getChangeType());
              }
              break;
            case SYNC_AFTER_FLUSH:
              switch (c.getChangeType()) {
                case ADD:
                  l.addedSync(c.getTransientEntity());
                  break;
                case UPDATE:
                  l.updatedSync(TransientStoreUtil.readonlyCopy(c), c.getTransientEntity());
                  break;
                case REMOVE:
                  l.removedSync(TransientStoreUtil.readonlyCopy(c));
                  break;
                default:
                  throw new IllegalArgumentException("Illegal arguments " + where + ":" + c.getChangeType());
              }
              break;
            case ASYNC_AFTER_FLUSH:
              switch (c.getChangeType()) {
                case ADD:
                  l.addedAsync(c.getTransientEntity());
                  break;
                case UPDATE:
                  l.updatedAsync(TransientStoreUtil.readonlyCopy(c), c.getTransientEntity());
                  break;
                case REMOVE:
                  l.removedAsync(TransientStoreUtil.readonlyCopy(c));
                  break;
                default:
                  throw new IllegalArgumentException("Illegal arguments " + where + ":" + c.getChangeType());
              }
              break;
            default:
              throw new IllegalArgumentException("Illegal arguments " + where + ":" + c.getChangeType());
          }
        } catch (Exception e) {
          if (log.isErrorEnabled()) {
            log.error("Exception while notifying entity listener.", e);
          }
          // rethrow exception only for beforeFlush listeners 
          if (where == Where.SYNC_BEFORE_CONSTRAINTS || where == Where.SYNC_BEFORE_FLUSH) {
            if (e instanceof RuntimeException) {
              throw (RuntimeException) e;
            }
            throw new RuntimeException(e);
          }
        }
      }
    }
  }

  @Nullable
  public static EventsMultiplexer getInstance() {
    // this method may be called by global beans on global scope shutdown 
    // as a result, eventsMultiplexer may be removed already 
    try {
      return ((EventsMultiplexer) ServiceLocator.getBean("eventsMultiplexer"));
    } catch (IllegalStateException e) {
      if (log.isWarnEnabled()) {
        log.warn("EventMultiplexer already disposed: " + e.getMessage());
      }
      return null;
    }
  }

  public static void removeListenerSafe(Entity e, IEntityListener listener) {
    check_9klgcu_a0a1(getInstance(), e, listener);
  }

  public static void removeListenerSafe(String type, IEntityListener listener) {
    check_9klgcu_a0a2(getInstance(), type, listener);
  }

  private static void check_9klgcu_a0a1(EventsMultiplexer checkedDotOperand, Entity e, IEntityListener listener) {
    if (null != checkedDotOperand) {
      checkedDotOperand.removeListener(e, listener);
    }

  }

  private static void check_9klgcu_a0a2(EventsMultiplexer checkedDotOperand, String type, IEntityListener listener) {
    if (null != checkedDotOperand) {
      checkedDotOperand.removeListener(type, listener);
    }

  }

  public static class JobImpl extends Job {
    private Set<TransientEntityChange> changes;
    private EventsMultiplexer eventsMultiplexer;

    public JobImpl(EventsMultiplexer eventsMultiplexer, Set<TransientEntityChange> changes) {
      this.eventsMultiplexer = eventsMultiplexer;
      this.changes = changes;
    }

    public void execute() throws Throwable {
      {
        boolean $nt$_9klgcu_a0a0 = DnqUtils.getCurrentTransientSession() == null;
        final TransientStoreSession ts1_9klgcu_a0a0 = DnqUtils.beginTransientSession("execute_0");
        try {
          this.eventsMultiplexer.fire(Where.ASYNC_AFTER_FLUSH, this.changes);
        } catch (Throwable _ex_) {
          if ($nt$_9klgcu_a0a0) {
            TransientStoreUtil.abort(_ex_, ts1_9klgcu_a0a0);
          }
          if (_ex_ instanceof RuntimeException) {
            throw (RuntimeException) _ex_;
          } else {
            throw new RuntimeException(_ex_);
          }
        } finally {
          if ($nt$_9klgcu_a0a0) {
            TransientStoreUtil.commit(ts1_9klgcu_a0a0);
          }
        }
      }
    }
  }

  private class FullEntityId {
    private final int storeHashCode;
    private final int entityTypeId;
    private final long entityLocalId;

    private FullEntityId(final EntityStore store, final EntityId id) {
      storeHashCode = System.identityHashCode(store);
      entityTypeId = id.getTypeId();
      entityLocalId = id.getLocalId();
    }

    @Override
    public boolean equals(Object object) {
      if (this == object) {
        return true;
      }
      EventsMultiplexer.FullEntityId that = (EventsMultiplexer.FullEntityId) object;
      if (storeHashCode != that.storeHashCode) {
        return false;
      }
      if (entityLocalId != that.entityLocalId) {
        return false;
      }
      if (entityTypeId != that.entityTypeId) {
        return false;
      }
      return true;
    }

    @Override
    public int hashCode() {
      int result = storeHashCode;
      result = 31 * result + entityTypeId;
      result = 31 * result + (int) (entityLocalId ^ (entityLocalId >> 32));
      return result;
    }

    @Override
    public String toString() {
      final StringBuilder builder = new StringBuilder(10);
      toString(builder);
      return builder.toString();
    }

    public void toString(final StringBuilder builder) {
      builder.append(entityTypeId);
      builder.append('-');
      builder.append(entityLocalId);
      builder.append('@');
      builder.append(storeHashCode);
    }
  }
}
