/**
 * Copyright 2006 - 2018 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.teamsys.dnq.database;

import jetbrains.exodus.core.dataStructures.StablePriorityQueue;
import jetbrains.exodus.database.*;
import jetbrains.exodus.entitystore.*;
import jetbrains.exodus.env.EnvironmentConfig;
import jetbrains.exodus.query.QueryEngine;
import jetbrains.exodus.query.metadata.ModelMetaData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @author Vadim.Gurov
 */
public class TransientEntityStoreImpl implements TransientEntityStore {

    private static final Logger logger = LoggerFactory.getLogger(TransientEntityStoreImpl.class);

    @NotNull
    private EntityStore persistentStore;
    @NotNull
    private QueryEngine queryEngine;
    @Nullable
    private ModelMetaData modelMetaData;
    @Nullable
    private IEventsMultiplexer eventsMultiplexer;
    @NotNull
    private final Set<TransientStoreSession> sessions =
            Collections.newSetFromMap(new ConcurrentHashMap<TransientStoreSession, Boolean>(200));
    @NotNull
    private final ThreadLocal<TransientStoreSession> currentSession = new ThreadLocal<>();
    @NotNull
    private final StablePriorityQueue<Integer, TransientStoreSessionListener> listeners = new StablePriorityQueue<>();

    private volatile boolean open = true;
    private boolean closed = false;
    @NotNull
    private final Map<String, Entity> enumCache = new ConcurrentHashMap<>();
    @NotNull
    private final Map<String, BasePersistentClassImpl> persistentClassInstanceCache = new ConcurrentHashMap<>();
    @NotNull
    private final Map<Class, BasePersistentClassImpl> persistentClassInstances = new ConcurrentHashMap<>();

    @NotNull
    final ReentrantLock flushLock = new ReentrantLock(true); // fair flushLock

    public TransientEntityStoreImpl() {
        if (logger.isTraceEnabled()) {
            logger.trace("TransientEntityStoreImpl constructor called.");
        }
    }

    @NotNull
    public EntityStore getPersistentStore() {
        return persistentStore;
    }

    @NotNull
    public QueryEngine getQueryEngine() {
        return queryEngine;
    }

    @Nullable
    public IEventsMultiplexer getEventsMultiplexer() {
        return eventsMultiplexer;
    }

    public void setEventsMultiplexer(@Nullable IEventsMultiplexer eventsMultiplexer) {
        this.eventsMultiplexer = eventsMultiplexer;
    }

    /**
     * Must be injected.
     *
     * @param persistentStore persistent entity store.
     */
    public void setPersistentStore(@NotNull EntityStore persistentStore) {
        final EnvironmentConfig ec = ((PersistentEntityStore) persistentStore).getEnvironment().getEnvironmentConfig();
        if (ec.getEnvTxnDowngradeAfterFlush() == EnvironmentConfig.DEFAULT.getEnvTxnDowngradeAfterFlush()) {
            ec.setEnvTxnDowngradeAfterFlush(false);
        }
        ec.setEnvTxnReplayMaxCount(Integer.MAX_VALUE);
        ec.setEnvTxnReplayTimeout(Long.MAX_VALUE);
        ec.setGcUseExclusiveTransaction(true);
        this.persistentStore = persistentStore;
    }

    /**
     * Must be injected.
     *
     * @param queryEngine query engine.
     */
    public void setQueryEngine(@NotNull QueryEngine queryEngine) {
        this.queryEngine = queryEngine;
    }

    @NotNull
    public String getName() {
        return "transient store";
    }

    @NotNull
    public String getLocation() {
        throw new UnsupportedOperationException("Not supported by transient store.");
    }

    @NotNull
    @Override
    public StoreTransaction beginTransaction() {
        throw new UnsupportedOperationException();
    }

    @NotNull
    @Override
    public StoreTransaction beginExclusiveTransaction() {
        throw new UnsupportedOperationException();
    }

    @NotNull
    @Override
    public TransientStoreSession beginReadonlyTransaction() {
        return registerStoreSession(new TransientSessionImpl(this, true));
    }

    @Nullable
    @Override
    public StoreTransaction getCurrentTransaction() {
        throw new UnsupportedOperationException();
    }

    @NotNull
    public TransientStoreSession beginSession() {
        assertOpen();

        if (logger.isDebugEnabled()) {
            logger.debug("Begin new session");
        }

        TransientStoreSession currentSession = this.currentSession.get();
        if (currentSession != null) {
            logger.debug("Return session already associated with the current thread " + currentSession);
            return currentSession;
        }

        return registerStoreSession(new TransientSessionImpl(this, false));
    }

    public void resumeSession(@Nullable TransientStoreSession session) {
        if (session != null) {
            assertOpen();

            TransientStoreSession current = currentSession.get();
            if (current != null) {
                if (current != session) {
                    throw new IllegalStateException("Another open transient session already associated with current thread.");
                }
            }

            currentSession.set(session);
        }
    }

    public void setModelMetaData(@Nullable final ModelMetaData modelMetaData) {
        this.modelMetaData = modelMetaData;
    }

    @Nullable
    public ModelMetaData getModelMetaData() {
        return modelMetaData;
    }

    /**
     * It's guaranteed that current thread session is Open, if exists
     *
     * @return current thread session
     */
    @Nullable
    public TransientStoreSession getThreadSession() {
        return currentSession.get();
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    public void close() {
        open = false;

        if (eventsMultiplexer != null) {
            eventsMultiplexer.onClose(this);
        }

        logger.info("Close transient store.");
        closed = true;

        int sessionsSize = sessions.size();
        if (sessionsSize > 0) {
            logger.warn("There're " + sessionsSize + " open transient sessions. Print.");
            if (logger.isDebugEnabled()) {
                for (TransientStoreSession session : sessions) {
                    TransientSessionImpl impl = session instanceof TransientSessionImpl ? (TransientSessionImpl) session : null;
                    if (impl != null) {
                        logger.warn("Not closed session stack trace: ", impl.getStack());
                    }
                }
            }
        }
    }

    public boolean entityTypeExists(@NotNull final String entityTypeName) {
        try {
            return ((PersistentEntityStore) persistentStore).getEntityTypeId(entityTypeName) >= 0;
        } catch (Exception e) {
            // ignore
        }
        return false;
    }

    public void renameEntityTypeRefactoring(@NotNull final String oldEntityTypeName, @NotNull final String newEntityTypeName) {
        final TransientSessionImpl s = (TransientSessionImpl) getThreadSession();

        if (s == null) {
            throw new IllegalStateException("No current thread session.");
        }

        s.addChangeAndRun(new TransientSessionImpl.MyRunnable() {
            @Override
            public boolean run() {
                ((PersistentEntityStore) s.getPersistentTransaction().getStore()).renameEntityType(oldEntityTypeName, newEntityTypeName);
                return true;
            }
        });
    }

    public void deleteEntityTypeRefactoring(@NotNull final String entityTypeName) {
        final TransientSessionImpl s = (TransientSessionImpl) getThreadSession();

        if (s == null) {
            throw new IllegalStateException("No current thread session.");
        }

        s.addChangeAndRun(new TransientSessionImpl.MyRunnable() {
            @Override
            public boolean run() {
                ((PersistentEntityStoreImpl) s.getPersistentTransaction().getStore()).deleteEntityType(entityTypeName);
                return true;
            }
        });
    }

    public void deleteEntityRefactoring(@NotNull Entity entity) {
        final TransientSessionImpl s = (TransientSessionImpl) getThreadSession();

        if (s == null) {
            throw new IllegalStateException("No current thread session.");
        }

        final Entity persistentEntity = (entity instanceof TransientEntity) ? ((TransientEntity) entity).getPersistentEntity() : entity;
        if (entity instanceof TransientEntity) {
            s.deleteEntity((TransientEntity) entity);
        } else {
            s.addChangeAndRun(new TransientSessionImpl.MyRunnable() {
                public boolean run() {
                    persistentEntity.delete();
                    return true;
                }
            });
        }
    }

    public void deleteLinksRefactoring(@NotNull final Entity entity, @NotNull final String linkName) {
        final TransientSessionImpl s = (TransientSessionImpl) getThreadSession();

        if (s == null) {
            throw new IllegalStateException("No current thread session.");
        }

        final Entity persistentEntity = (entity instanceof TransientEntity) ? ((TransientEntity) entity).getPersistentEntity() : entity;
        s.addChangeAndRun(new TransientSessionImpl.MyRunnable() {
            public boolean run() {
                persistentEntity.deleteLinks(linkName);
                return true;
            }
        });
    }

    public void deleteLinkRefactoring(@NotNull final Entity entity, @NotNull final String linkName, @NotNull final Entity link) {
        final TransientSessionImpl s = (TransientSessionImpl) getThreadSession();

        if (s == null) {
            throw new IllegalStateException("No current thread session.");
        }

        final Entity persistentEntity = (entity instanceof TransientEntity) ? ((TransientEntity) entity).getPersistentEntity() : entity;
        final Entity persistentLink = (link instanceof TransientEntity) ? ((TransientEntity) link).getPersistentEntity() : link;

        s.addChangeAndRun(new TransientSessionImpl.MyRunnable() {
            public boolean run() {
                persistentEntity.deleteLink(linkName, persistentLink);
                return true;
            }
        });
    }

    @NotNull
    private TransientStoreSession registerStoreSession(@NotNull TransientStoreSession s) {
        if (!sessions.add(s)) {
            throw new IllegalArgumentException("Session is already registered.");
        }

        currentSession.set(s);

        return s;
    }

    void unregisterStoreSession(@NotNull TransientStoreSession s) {
        if (!sessions.remove(s)) {
            throw new IllegalArgumentException("Transient session wasn't previously registered.");
        }

        currentSession.remove();
    }

    @Nullable
    public TransientStoreSession suspendThreadSession() {
        assertOpen();

        final TransientStoreSession current = getThreadSession();
        if (current != null) {
            currentSession.remove();
        }

        return current;
    }

    public void addListener(@NotNull TransientStoreSessionListener listener) {
        listeners.push(0, listener);
    }

    @Override
    public void addListener(@NotNull TransientStoreSessionListener listener, final int priority) {
        listeners.push(priority, listener);
    }

    public void removeListener(@NotNull TransientStoreSessionListener listener) {
        listeners.remove(listener);
    }

    void forAllListeners(@NotNull ListenerVisitor v) {
        for (final TransientStoreSessionListener listener : listeners) {
            v.visit(listener);
        }
    }

    public int sessionsCount() {
        return sessions.size();
    }

    public void dumpSessions(@NotNull StringBuilder sb) {
        for (TransientStoreSession s : sessions) {
            sb.append("\n").append(s.toString());
        }
    }

    @Nullable
    public Entity getCachedEnumValue(@NotNull final String className, @NotNull final String propName) {
        return enumCache.get(getEnumKey(className, propName));
    }

    public void setCachedEnumValue(@NotNull final String className,
                                   @NotNull final String propName,
                                   @NotNull final Entity entity) {
        enumCache.put(getEnumKey(className, propName), entity);
    }

    @Nullable
    public BasePersistentClassImpl getCachedPersistentClassInstance(@NotNull final String entityType) {
        return persistentClassInstanceCache.get(entityType);
    }

    @Nullable
    public BasePersistentClassImpl getCachedPersistentClassInstance(@NotNull final Class<? extends BasePersistentClassImpl> entityType) {
        return persistentClassInstances.get(entityType);
    }

    public void setCachedPersistentClassInstance(@NotNull final String entityType, @NotNull final BasePersistentClassImpl instance) {
        persistentClassInstanceCache.put(entityType, instance);
        Class<? extends BasePersistentClassImpl> clazz = instance.getClass();
        if (persistentClassInstances.get(clazz) != null) {
            if (logger.isWarnEnabled()) {
                logger.warn("Persistent class instance already registered for: " + clazz.getSimpleName());
            }
        }
        persistentClassInstances.put(clazz, instance);
    }

    private void assertOpen() {
        // this flag isn't even volatile, but this is legacy behavior
        if (closed) throw new IllegalStateException("Transient store is closed.");
    }

    @NotNull
    public static String getEnumKey(@NotNull final String className, @NotNull final String propName) {
        return propName + '@' + className;
    }

    interface ListenerVisitor {
        void visit(@NotNull TransientStoreSessionListener listener);
    }

}
