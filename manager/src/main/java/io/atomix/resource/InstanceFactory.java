/*
 * Copyright 2015 the original author or authors.
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
 * limitations under the License
 */
package io.atomix.resource;

import io.atomix.ResourceException;
import io.atomix.catalyst.serializer.Serializer;
import io.atomix.catalyst.transport.Transport;
import io.atomix.catalyst.util.Assert;
import io.atomix.catalyst.util.Managed;
import io.atomix.catalyst.util.concurrent.ThreadContext;
import io.atomix.copycat.client.CopycatClient;
import io.atomix.copycat.client.RaftClient;
import io.atomix.copycat.client.RecoveryStrategy;
import io.atomix.copycat.client.session.Session;
import io.atomix.manager.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Resource instance factory.
 *
 * @author <a href="http://github.com/kuujo>Jordan Halterman</a>
 */
public class InstanceFactory implements Managed<InstanceFactory> {
  private static final Logger LOGGER = LoggerFactory.getLogger(InstanceFactory.class);
  private final RaftClient client;
  private final Transport transport;
  private final Map<Long, Instance> instances = new ConcurrentHashMap<>();

  public InstanceFactory(CopycatClient.Builder clientBuilder, Transport transport) {
    this.client = Assert.notNull(clientBuilder, "clientBuilder").withRecoveryStrategy(new InstanceRecoveryStrategy()).build();
    this.transport = Assert.notNull(transport, "transport");
  }

  /**
   * Returns the client thread context.
   * <p>
   * This context is representative of the thread on which asynchronous callbacks will be executed for this
   * Atomix instance. Atomix guarantees that all {@link CompletableFuture}s supplied by this instance will
   * be executed via the returned context. Users can use the context to access the thread-local
   * {@link Serializer}.
   *
   * @return The client thread context.
   */
  public ThreadContext context() {
    return client.context();
  }

  /**
   * Returns the client session.
   *
   * @return The current underlying session.
   */
  public Session session() {
    return client.session();
  }

  /**
   * Checks whether a resource exists with the given key.
   * <p>
   * If no resource with the given {@code key} exists in the cluster, the returned {@link CompletableFuture} will
   * be completed {@code false}. Note, however, that users should not significantly rely upon the existence or
   * non-existence of a resource due to race conditions. While a resource may not exist when the returned future is
   * completed, it may be created by another node shortly thereafter.
   *
   * @param key The key to check.
   * @return A completable future indicating whether the given key exists.
   * @throws NullPointerException if {@code key} is null
   */
  public CompletableFuture<Boolean> exists(String key) {
    return client.submit(new ResourceExists(key));
  }

  /**
   * Gets or creates the given resource and acquires a singleton reference to it.
   * <p>
   * If a resource at the given key already exists, the resource will be validated to verify that its type
   * matches the given type. If no resource yet exists, a new resource will be created in the cluster. Once
   * the session for the resource has been opened, a resource instance will be returned.
   * <p>
   * The returned {@link Resource} instance will be a singleton reference to an global instance for this node.
   * That is, multiple calls to this method for the same resource will result in the same {@link Resource}
   * instance being returned.
   *
   * @param key The key at which to get the resource.
   * @param type The expected resource type.
   * @param <T> The resource type.
   * @return A completable future to be completed once the resource has been loaded.
   * @throws NullPointerException if {@code key} or {@code type} are null
   */
  @SuppressWarnings("unchecked")
  public <T extends Resource> CompletableFuture<T> get(String key, Class<? super T> type) {
    return get(key, type, (client) -> {
      try {
        return (T) type.getConstructor(RaftClient.class).newInstance(client);
      } catch (NoSuchMethodException | InvocationTargetException | InstantiationException | IllegalAccessException e) {
        throw new RuntimeException(e);
      }
    });
  }

  /**
   * Gets or creates the given resource and acquires a singleton reference to it.
   * <p>
   * If a resource at the given key already exists, the resource will be validated to verify that its type
   * matches the given type. If no resource yet exists, a new resource will be created in the cluster. Once
   * the session for the resource has been opened, a resource instance will be returned.
   * <p>
   * The returned {@link Resource} instance will be a singleton reference to an global instance for this node.
   * That is, multiple calls to this method for the same resource will result in the same {@link Resource}
   * instance being returned.
   *
   * @param key The key at which to get the resource.
   * @param factory The resource factory.
   * @param <T> The resource type.
   * @return A completable future to be completed once the resource has been loaded.
   * @throws NullPointerException if {@code key} or {@code factory} are null
   */
  @SuppressWarnings("unchecked")
  public <T extends Resource> CompletableFuture<T> get(String key, Class<? super T> type, Function<RaftClient, T> factory) {
    ResourceInfo info = Resources.getInfo(type);
    return client.submit(new GetResource(Assert.notNull(key, "key"), info.stateMachine()))
      .thenApply(id -> instances.computeIfAbsent(id, i -> {
        T instance = factory.apply(new InstanceClient(id, client, transport));
        return new Instance<>(key, type, Instance.Method.GET, instance);
      })).thenApply(instance -> (T) instance.instance);
  }

  /**
   * Creates a new instance for the given resource.
   * <p>
   * If a resource at the given key already exists, the resource will be validated to verify that its type
   * matches the given type. If no resource yet exists, a new resource will be created in the cluster. Once
   * the session for the resource has been opened, a new resource instance will be returned.
   * <p>
   * The returned {@link Resource} instance will have a unique logical connection to the resource state. This
   * means that operations and events submitted or received by this instance related to this instance only,
   * even if multiple instances of the resource are open on this node. For instance, a lock resource created
   * via this method will behave as a unique reference to the distributed state. Locking a lock acquired via this
   * method will lock <em>only</em> that lock instance and not other instance of the lock on this node.
   * <p>
   * To acquire a singleton reference to a resource that is global to this node, use the {@link #get(String, Class)}
   * method.
   *
   * @param key The key at which to create the resource.
   * @param type The resource type to create.
   * @param <T> The resource type.
   * @return A completable future to be completed once the resource has been created.
   * @throws NullPointerException if {@code key} or {@code type} are null
   * @throws ResourceException if the resource could not be instantiated
   */
  @SuppressWarnings("unchecked")
  public <T extends Resource> CompletableFuture<T> create(String key, Class<? super T> type) {
    return create(key, type, client -> {
      try {
        return (T) type.getConstructor(RaftClient.class).newInstance(client);
      } catch (NoSuchMethodException | InvocationTargetException | InstantiationException | IllegalAccessException e) {
        throw new ResourceException(e);
      }
    });
  }

  /**
   * Creates a new instance for the given resource.
   * <p>
   * If a resource at the given key already exists, the resource will be validated to verify that its type
   * matches the given type. If no resource yet exists, a new resource will be created in the cluster. Once
   * the session for the resource has been opened, a new resource instance will be returned.
   * <p>
   * The returned {@link Resource} instance will have a unique logical connection to the resource state. This
   * means that operations and events submitted or received by this instance related to this instance only,
   * even if multiple instances of the resource are open on this node. For instance, a lock resource created
   * via this method will behave as a unique reference to the distributed state. Locking a lock acquired via this
   * method will lock <em>only</em> that lock instance and not other instance of the lock on this node.
   * <p>
   * To acquire a singleton reference to a resource that is global to this node, use the {@link #get(String, Class)}
   * method.
   *
   * @param key The key at which to create the resource.
   * @param factory The resource factory.
   * @param <T> The resource type.
   * @return A completable future to be completed once the resource has been created.
   * @throws NullPointerException if {@code key} or {@code factory} are null
   */
  @SuppressWarnings("unchecked")
  public <T extends Resource> CompletableFuture<T> create(String key, Class<? super T> type, Function<RaftClient, T> factory) {
    ResourceInfo info = Resources.getInfo(type);
    return client.submit(new CreateResource(Assert.notNull(key, "key"), info.stateMachine()))
      .thenApply(id -> instances.computeIfAbsent(id, i -> {
        T instance = factory.apply(new InstanceClient(id, client, transport));
        return new Instance<>(key, type, Instance.Method.CREATE, instance);
      })).thenApply(instance -> (T) instance.instance);
  }

  /**
   * Opens the instance factory.
   *
   * @return A completable future to be completed once the instance is open.
   */
  @Override
  public CompletableFuture<InstanceFactory> open() {
    return client.open().thenApply(v -> this);
  }

  /**
   * Returns a boolean value indicating whether the instance factory is open.
   *
   * @return Indicates whether the instance factory is open.
   */
  @Override
  public boolean isOpen() {
    return client.isOpen();
  }

  /**
   * Closes the instance factory.
   *
   * @return A completable future to be completed once the instance factory is closed.
   */
  @Override
  public CompletableFuture<Void> close() {
    return client.close();
  }

  /**
   * Returns a boolean value indicating whether the instance factory is closed.
   *
   * @return Indicates whether the instance factory is closed.
   */
  @Override
  public boolean isClosed() {
    return client.isClosed();
  }

  /**
   * Resource instance recovery strategy.
   */
  private class InstanceRecoveryStrategy implements RecoveryStrategy {
    @Override
    public void recover(RaftClient client) {
      client.open().whenComplete((result, error) -> {
        if (error == null) {
          recoverResources(client);
        } else {
          LOGGER.warn("Failed to recover client session");
        }
      });
    }

    /**
     * Recovers resources.
     */
    private void recoverResources(RaftClient client) {
      recoverResources(client, instances.entrySet().iterator(), new CompletableFuture<>()).whenComplete((result, error) -> {
        if (error == null) {
          LOGGER.info("Recovered {} resources", instances.size());
        } else {
          LOGGER.warn("Failed to recover resources");
        }
      });
    }

    /**
     * Recursively recovers resources.
     */
    private CompletableFuture<Void> recoverResources(RaftClient client, Iterator<Map.Entry<Long, Instance>> iterator, CompletableFuture<Void> future) {
      if (iterator.hasNext()) {
        recoverResource(client, iterator, future);
      } else {
        future.complete(null);
      }
      return future;
    }

    /**
     * Recovers a single resource.
     */
    private void recoverResource(RaftClient client, Iterator<Map.Entry<Long, Instance>> iterator, CompletableFuture<Void> future) {
      Instance instance = iterator.next().getValue();
      iterator.remove();
      LOGGER.debug("Recovering resource: {}", instance.key);
      if (instance.method == Instance.Method.GET) {
        recoverGet(instance, client, iterator, future);
      } else if (instance.method == Instance.Method.CREATE) {
        recoverCreate(instance, client, iterator, future);
      }
    }

    /**
     * Recovers a resource created via the get method.
     */
    private void recoverGet(Instance instance, RaftClient client, Iterator<Map.Entry<Long, Instance>> iterator, CompletableFuture<Void> future) {
      ResourceInfo info = Resources.getInfo(instance.type);
      client.submit(new GetResourceIfExists(instance.key, info.stateMachine()))
        .whenComplete(recoverComplete(instance, client, iterator, future));
    }

    /**
     * Recovers a resource created via the create method.
     */
    private void recoverCreate(Instance instance, RaftClient client, Iterator<Map.Entry<Long, Instance>> iterator, CompletableFuture<Void> future) {
      ResourceInfo info = Resources.getInfo(instance.type);
      client.submit(new CreateResourceIfExists(instance.key, info.stateMachine()))
        .whenComplete(recoverComplete(instance, client, iterator, future));
    }

    /**
     * Returns a function to complete the recovery of a resource.
     */
    private BiConsumer<Long, Throwable> recoverComplete(Instance instance, RaftClient client, Iterator<Map.Entry<Long, Instance>> iterator, CompletableFuture<Void> future) {
      return (result, error) -> {
        if (error == null) {
          if (result > 0) {
            instance.instance.reset(new InstanceClient(result, client, transport));
            instances.put(result, instance);
            recoverResources(client, iterator, future);
          }
        } else {
          LOGGER.warn("Failed to recover resource: {}", instance.key);
        }
      };
    }

  }

  /**
   * Resource instance.
   */
  private static class Instance<T extends Resource> {

    /**
     * The method with which the resource was opened.
     */
    private enum Method {
      GET,
      CREATE
    }

    private final String key;
    private final Class<? super T> type;
    private final Method method;
    private final T instance;

    private Instance(String key, Class<? super T> type, Method method, T instance) {
      this.key = key;
      this.type = type;
      this.method = method;
      this.instance = instance;
    }
  }

}
