package org.bbottema.clusteredobjectpool.core;

import lombok.Getter;
import org.bbottema.clusteredobjectpool.core.api.LoadBalancingStrategy;
import org.bbottema.clusteredobjectpool.core.api.ResourceKey;
import org.bbottema.clusteredobjectpool.util.CompositeFuturesAsFutureTask;
import org.bbottema.genericobjectpool.ExpirationPolicy;
import org.bbottema.genericobjectpool.GenericObjectPool;
import org.bbottema.genericobjectpool.PoolConfig;
import org.bbottema.genericobjectpool.PoolableObject;
import org.bbottema.genericobjectpool.util.Timeout;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.function.Predicate;

import static java.lang.String.format;

/**
 * Collection of clusters, each containing a number of (generic-object-pool) resource pools. Relies on the native generic-object-pool behavior for
 * auto-replenishing and pre-allocating resources (so always allocates / fills up to max pool size).
 * <p>
 * Can be used to configure high performance clusters of expensive resources that have a time-to-live.
 * <p>
 * <strong>Example use case:</strong><br>
 * Say you have two different mail clusters, each with several servers and each server able to handle multiple concurrent SMTP connections.<br>
 * The two cluster keys to the clusters, where each server is accessed round robin, and the objects in the respective cluster-pool are concurrent
 * open connections to the same mail server.
 * <ol>
 *    <li>Cluster 1, serverA, serverB, serverC</li>
 *    <li>Cluster 2, serverD, serverE</li>
 * </ol>
 *
 * @param <ClusterKey> Anything, to identify a group of resources pools. For example {@code String} if your cluster
 *  *                  keys are going to be "cluster1", "cluster2" etc. Or {@code UUID} for uuid's (or again String for {@code UUID.toString()}).
 * @param <PoolKey>    The resource for which multiple objects will be created in a generic-object-pool object pool.<br>
 *                     For example a javax.mail {@code Session} object for which multiple {@code Transport} connections can be created.
 * @param <T>          The resulting {@link PoolableObject} object that the allocators will create for the pool key.<br>
 *                     Continuing the example above, if the {@code PoolKey} is {@code Session}, then {@code T} could be {@code Transport}.
 */
@SuppressWarnings("unused")
public class ResourceClusters<ClusterKey, PoolKey, T> {

	@NotNull private final Map<ClusterKey, ResourcePools<PoolKey, T>> resourceClusters = new HashMap<>();
	@NotNull private final Map<ClusterKey, ClusterConfig<ClusterKey, PoolKey, T>> resourceClusterConfigs = new HashMap<>();
	@Getter
	@NotNull private final ClusterConfig<ClusterKey, PoolKey, T> clusterConfig;

	@SuppressWarnings({"unused", "unchecked"})
	public ResourceClusters(final ClusterConfig<ClusterKey, PoolKey, T> clusterConfig) {
		this.clusterConfig = clusterConfig;
	}

	/**
	 * Registers a cluster-specific configuration. Pools registered for this cluster afterwards use these values as their defaults.
	 *
	 * @throws IllegalArgumentException if the cluster already exists.
	 */
	public synchronized void registerResourceCluster(@NotNull final ClusterKey clusterKey,
													 @NotNull final ClusterConfig<ClusterKey, PoolKey, T> clusterConfig) throws IllegalArgumentException {
		if (resourceClusters.containsKey(clusterKey)) {
			throw new IllegalArgumentException("Cluster already exists for key " + clusterKey);
		}
		resourceClusterConfigs.put(clusterKey, clusterConfig);
		findOrCreateCluster(clusterKey);
	}
	
	/**
	 * Delegates to {@link #registerResourcePool(ResourceKey, ExpirationPolicy, int, int)}, using the global defaults for expiration policy, max pool size and sizing mode.
	 */
	@SuppressWarnings("unused")
	public void registerResourcePool(ResourceKey<ClusterKey, PoolKey> key) {
		final ClusterConfig<ClusterKey, PoolKey, T> clusterConfig = getClusterConfig(key.getClusterKey());
		registerResourcePool(key, clusterConfig.getDefaultExpirationPolicy(), clusterConfig.getDefaultCorePoolSize(), clusterConfig.getDefaultMaxPoolSize());
	}
	
	/**
	 * Registers a new pool for the given cluster. If the cluster is new as well, it will also be created. The new pool is backed by a {@link GenericObjectPool}.
	 *
	 * @throws IllegalArgumentException if the pool already exists in the specified cluster.
	 */
	@SuppressWarnings("WeakerAccess")
	public synchronized void registerResourcePool(@NotNull final ResourceKey<ClusterKey, PoolKey> key,
									 @NotNull final ExpirationPolicy<T> expirationPolicy,
									 final int corePoolSize,
									 final int maxPoolSize) throws IllegalArgumentException {
		final ResourcePools<PoolKey, T> cluster = findOrCreateCluster(key.getClusterKey());

		if (cluster.containsPool(key.getPoolKey())) {
			throw new IllegalArgumentException("Pool already exists for " + key);
		}
		
		final GenericObjectPool<T> pool = new GenericObjectPool<>(PoolConfig.<T>builder()
				.corePoolsize(corePoolSize)
				.maxPoolsize(maxPoolSize)
				.expirationPolicy(expirationPolicy)
				.build(), clusterConfig.getAllocatorFactory().create(key));
		
		cluster.add(new ResourcePool<>(key.getPoolKey(), pool));
	}
	
	/**
	 * @return If a cluster and pool combination is registered as a known pool.
	 */
	public boolean isPoolRegistered(@NotNull final ResourceKey<ClusterKey, PoolKey> key) {
		return resourceClusters.containsKey(key.getClusterKey()) &&
				resourceClusters.get(key.getClusterKey()).containsPool(key.getPoolKey());
	}

	/**
	 * @return If a cluster is registered as a known cluster.
	 */
	public boolean isClusterRegistered(@NotNull final ClusterKey clusterKey) {
		return resourceClusters.containsKey(clusterKey);
	}

	/**
	 * @return The cluster-specific config, or the global defaults if the cluster was not registered with specific config.
	 */
	@NotNull
	public ClusterConfig<ClusterKey, PoolKey, T> getClusterConfig(@NotNull final ClusterKey clusterKey) {
		return resourceClusterConfigs.containsKey(clusterKey)
				? resourceClusterConfigs.get(clusterKey)
				: clusterConfig;
	}
	
	/**
	 * Tries to claim the next resources from a pool in the given cluster. This cluster is assumed to have been populated with
	 * at least one pool already (because they can't be added without pool key).
	 * <p>
	 * Either preregister pools using {@link #claimResourceFromPool(ResourceKey)} or dynamically add pools on-the-fly
	 * using {@link #registerResourcePool(ResourceKey)} or {@link #registerResourcePool(ResourceKey, ExpirationPolicy, int, int)}.
	 */
	@Nullable
	public PoolableObject<T> claimResourceFromCluster(final ClusterKey clusterKey) throws InterruptedException {
		return cycleToNextPool(clusterKey).claim(getClusterConfig(clusterKey).getClaimTimeout());
	}
	
	/**
	 * Tries to claim the next resources from the pool in the given cluster. If the cluster key is unknown,
	 * a new cluster is created with one resources pool to draw from. If the pool key is unknown, a new pool
	 * to draw from is created for that key.
	 */
	@Nullable
	public PoolableObject<T> claimResourceFromPool(final ResourceKey<ClusterKey, PoolKey> key) throws InterruptedException {
		final ResourcePools<PoolKey, T> cluster = findOrCreateCluster(key.getClusterKey());
		if (!cluster.containsPool(key.getPoolKey())) {
			registerResourcePool(key);
		}
		return cluster.claimResource(key.getPoolKey(), getClusterConfig(key.getClusterKey()).getClaimTimeout());
	}

	/**
	 * Delegates to {@link #claimMatchingResourceFromPool(ResourceKey, Predicate, Timeout)}
	 * using the global claim timeout.
	 */
	@Nullable
	public PoolableObject<T> claimMatchingResourceFromPool(@NotNull final ResourceKey<ClusterKey, PoolKey> key,
														  @NotNull final Predicate<PoolableObject<T>> predicate) throws InterruptedException {
		return claimMatchingResourceFromPool(key, predicate, getClusterConfig(key.getClusterKey()).getClaimTimeout());
	}

	/**
	 * Claims an already available object matching the predicate from an already registered pool.
	 * <p>
	 * This method does not register new pools or allocate new resources. Keep the predicate fast and side-effect free;
	 * slow work such as ping/keep-alive checks should run after the resource has been claimed.
	 */
	@Nullable
	public PoolableObject<T> claimMatchingResourceFromPool(@NotNull final ResourceKey<ClusterKey, PoolKey> key,
														  @NotNull final Predicate<PoolableObject<T>> predicate,
														  @NotNull final Timeout claimTimeout) throws InterruptedException {
		final ResourcePools<PoolKey, T> cluster = resourceClusters.get(key.getClusterKey());
		if (cluster == null || !cluster.containsPool(key.getPoolKey())) {
			return null;
		}
		return cluster.claimMatchingResource(key.getPoolKey(), predicate, claimTimeout);
	}
	
	/**
	 * @return The number of resources currently allocated and ready to be used.
	 */
	public int countLiveResources() {
		int total = 0;
		for (final ResourcePools<PoolKey, T> resourcePools : resourceClusters.values()) {
			total += resourcePools.currentlyAllocated();
		}
		return total;
	}
	
	/**
	 * Delegates to {@link #shutdownPool(Object)} with empty pool key.
	 */
	@SuppressWarnings("UnusedReturnValue")
	public synchronized Future<?> shutDown() {
		return shutdownPool(null);
	}
	
	/**
	 * Tells all generic-object-pool pools [for the specified pool key] to shutdown and removes them from the clusters.<p>
	 * After calling this, the cluster is ready for more work as if it was just created.
	 */
	@SuppressWarnings("WeakerAccess")
	public synchronized Future<Void> shutdownPool(@Nullable final PoolKey key) {
		final List<Future<Void>> poolsShuttingDown = new ArrayList<>();
		for (final ResourcePools<PoolKey, T> resourcePools : resourceClusters.values()) {
			poolsShuttingDown.add(resourcePools.shutdownPool(key));
		}
		return CompositeFuturesAsFutureTask.ofFutures(poolsShuttingDown);
	}

	private synchronized ResourcePools<PoolKey, T> findOrCreateCluster(final ClusterKey clusterKey) {
		if (!resourceClusters.containsKey(clusterKey)) {
			Collection<ResourcePool<PoolKey, T>> collectionForCycling = getLoadBalancingStrategy(clusterKey).createCollectionForCycling();
			resourceClusters.put(clusterKey, new ResourcePools<>(collectionForCycling));
		}
		return resourceClusters.get(clusterKey);
	}

	private synchronized ResourcePool<PoolKey, T> cycleToNextPool(final ClusterKey clusterKey) {
		ResourcePools<PoolKey, T> cluster = findOrCreateCluster(clusterKey);
		if (cluster.getClusterCollection().isEmpty()) {
			throw new IllegalStateException(format("Cluster contains no pools to draw from for key '%s'", cluster));
		}
		return getLoadBalancingStrategy(clusterKey).cycle(cluster.getClusterCollection());
	}

	@SuppressWarnings("unchecked")
	@NotNull
	private LoadBalancingStrategy<ResourcePool<PoolKey, T>, Collection<ResourcePool<PoolKey, T>>> getLoadBalancingStrategy(final ClusterKey clusterKey) {
		return getClusterConfig(clusterKey).getLoadBalancingStrategy();
	}
}
