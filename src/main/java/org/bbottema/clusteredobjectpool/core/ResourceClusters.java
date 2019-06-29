package org.bbottema.clusteredobjectpool.core;

import org.bbottema.clusteredobjectpool.core.api.CyclingStrategy;
import org.bbottema.clusteredobjectpool.core.api.ResourceKey;
import org.bbottema.genericobjectpool.ExpirationPolicy;
import org.bbottema.genericobjectpool.GenericObjectPool;
import org.bbottema.genericobjectpool.PoolConfig;
import org.bbottema.genericobjectpool.PoolableObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

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
	@NotNull private final ClusterConfig<PoolKey, T> clusterConfig;
	@NotNull private final CyclingStrategy<Collection<ResourcePool<PoolKey, T>>> cyclingStrategy;

	@SuppressWarnings({"unused", "unchecked"})
	public ResourceClusters(@NotNull final ClusterConfig<PoolKey, T> clusterConfig) {
		this.clusterConfig = clusterConfig;
		this.cyclingStrategy = clusterConfig.getCyclingStrategy();
	}
	
	/**
	 * Delegates to {@link #registerResourcePool(ResourceKey, ExpirationPolicy, int, int)}, using the global defaults for expiration policy, max pool size and sizing mode.
	 */
	@SuppressWarnings("unused")
	public void registerResourcePool(@NotNull ResourceKey<ClusterKey, PoolKey> key) {
		registerResourcePool(key, clusterConfig.getDefaultExpirationPolicy(), clusterConfig.getDefaultCorePoolSize(), clusterConfig.getDefaultMaxPoolSize());
	}
	
	/**
	 * Registers a new pool for the given cluster. If the cluster is new as well, it will also be created. The new pool is backed by a {@link GenericObjectPool}.
	 *
	 * @throws IllegalArgumentException if the pool already exists in the specified cluster.
	 */
	@SuppressWarnings("WeakerAccess")
	public void registerResourcePool(@NotNull final ResourceKey<ClusterKey, PoolKey> key,
									 @NotNull final ExpirationPolicy<T> expirationPolicy,
									 final int corePoolSize,
									 final int maxPoolSize) throws IllegalArgumentException {
		final ResourcePools<PoolKey, T> cluster = findCluster(key.getClusterKey());

		if (cluster.containsPool(key.getPoolKey())) {
			throw new IllegalArgumentException("Pool already exists for " + key);
		}
		
		final GenericObjectPool<T> pool = new GenericObjectPool<>(PoolConfig.<T>builder()
				.corePoolsize(corePoolSize)
				.maxPoolsize(maxPoolSize)
				.expirationPolicy(expirationPolicy)
				.build(), clusterConfig.getAllocatorFactory().create(key.getPoolKey()));
		
		cluster.add(new ResourcePool<>(key.getPoolKey(), pool));
	}
	
	/**
	 * Tries to claim the next resources from a pool in the given cluster. If the cluster key is unknown,
	 * a new cluster is created with one resources pool to draw from.
	 * <p>
	 * To use multiple resources pools in a cluster, use {@link #registerResourcePool(ResourceKey)} or
	 * {@link #registerResourcePool(ResourceKey, ExpirationPolicy, int, int)} instead.
	 */
	@Nullable
	public PoolableObject<T> claimResourceFromCluster(@NotNull final ClusterKey clusterKey) throws InterruptedException {
		return cycleToNextPool(clusterKey).claim(clusterConfig.getClaimTimeout());
	}
	
	/**
	 * Tries to claim the next resources from the pool in the given cluster. If the cluster key is unknown,
	 * a new cluster is created with one resources pool to draw from. If the pool key is unknown, a new pool
	 * to draw from is created for that key.
	 */
	@Nullable
	public PoolableObject<T> claimResourceFromPool(@NotNull final ResourceKey<ClusterKey, PoolKey> key) throws InterruptedException {
		final ResourcePools<PoolKey, T> cluster = findCluster(key.getClusterKey());
		if (!cluster.containsPool(key.getPoolKey())) {
			registerResourcePool(key);
		}
		return cluster.claimResource(key.getPoolKey(), clusterConfig.getClaimTimeout());
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
	 * Delegates to {@link #clearPool(Object)} with empty pool key.
	 */
	public synchronized void clearPools() {
		clearPool(null);
	}
	
	/**
	 * Tells all generic-object-pool pools [for the specified pool key] to shutdown and removes them from the clusters.<p>
	 * After calling this, the cluster is ready for more work as if it was just created.
	 */
	@SuppressWarnings("WeakerAccess")
	public synchronized void clearPool(@Nullable final PoolKey key) {
		for (final ResourcePools<PoolKey, T> resourcePools : resourceClusters.values()) {
			resourcePools.clearPool(key);
		}
	}

	private synchronized ResourcePools<PoolKey, T> findCluster(@NotNull final ClusterKey clusterKey) {
		if (!resourceClusters.containsKey(clusterKey)) {
			Collection<ResourcePool<PoolKey, T>> collectionForCycling = cyclingStrategy.createCollectionForCycling();
			resourceClusters.put(clusterKey, new ResourcePools<>(collectionForCycling));
		}
		return resourceClusters.get(clusterKey);
	}

	private synchronized ResourcePool<PoolKey, T> cycleToNextPool(@NotNull final ClusterKey clusterKey) {
		ResourcePools<PoolKey, T> cluster = findCluster(clusterKey);
		return cyclingStrategy.cycle(cluster.getClusterCollection());
	}
}