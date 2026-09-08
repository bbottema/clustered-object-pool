package org.bbottema.clusteredobjectpool.core;

import lombok.Getter;
import org.bbottema.clusteredobjectpool.core.api.LoadBalancingStrategy;
import org.bbottema.clusteredobjectpool.core.api.ResourceKey;
import org.bbottema.clusteredobjectpool.util.CompositeFuturesAsFutureTask;
import org.bbottema.genericobjectpool.AllocationContext;
import org.bbottema.genericobjectpool.ClaimOptions;
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
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

import static java.util.Objects.requireNonNull;

/**
 * Collection of clusters, each containing a number of (generic-object-pool) resource pools. Relies on the native generic-object-pool behavior for
 * auto-replenishing and pre-allocating resources (prefilling the configured core size, growing on demand up to the maximum).
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

	private final Lock registryLock = new ReentrantLock();
	// Existing strategies remain serialized, but they never hold the registry's bookkeeping lock.
	private final Lock selectionLock = new ReentrantLock();
	private final Map<ClusterKey, ResourcePools<PoolKey, T>> resourceClusters = new HashMap<>();
	private final Map<ClusterKey, ClusterConfig<ClusterKey, PoolKey, T>> resourceClusterConfigs = new HashMap<>();
	@Getter @NotNull private final ClusterConfig<ClusterKey, PoolKey, T> clusterConfig;

	public ResourceClusters(final ClusterConfig<ClusterKey, PoolKey, T> clusterConfig) {
		this.clusterConfig = requireNonNull(clusterConfig, "clusterConfig");
	}

	/**
	 * Registers cluster-specific defaults for pools registered afterwards.
	 *
	 * @throws IllegalArgumentException if the cluster already exists
	 */
	public void registerResourceCluster(@NotNull final ClusterKey key,
			@NotNull final ClusterConfig<ClusterKey, PoolKey, T> config) {
		final ResourcePools<PoolKey, T> cluster;
		registryLock.lock();
		try {
			if (resourceClusters.containsKey(key)) {
				throw new IllegalArgumentException("Cluster already exists for key " + key);
			}
			resourceClusterConfigs.put(key, config);
			cluster = createCluster(key);
		} finally {
			registryLock.unlock();
		}
		prepareLegacySelection(cluster);
	}

	/** Registers a pool using its cluster's default expiration policy and core/max sizing. */
	public void registerResourcePool(final ResourceKey<ClusterKey, PoolKey> key) {
		final ClusterConfig<ClusterKey, PoolKey, T> config = getClusterConfig(key.getClusterKey());
		registerResourcePool(key, config.getDefaultExpirationPolicy(), config.getDefaultCorePoolSize(), config.getDefaultMaxPoolSize());
	}

	/**
	 * Registers a new pool backed by Generic Object Pool; creates its cluster if necessary.
	 *
	 * @throws IllegalArgumentException if that pool is already registered
	 */
	public void registerResourcePool(@NotNull final ResourceKey<ClusterKey, PoolKey> key,
			@NotNull final ExpirationPolicy<T> expirationPolicy, final int corePoolSize, final int maxPoolSize) {
		try {
			final ResourcePools<PoolKey, T> cluster = findOrCreateCluster(key.getClusterKey(), null);
			cluster.register(key.getPoolKey(), () -> newPool(key, expirationPolicy, corePoolSize, maxPoolSize), true, null);
		} catch (InterruptedException interrupted) {
			// No interruptible wait is used by this legacy registration route.
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Pool registration interrupted", interrupted);
		}
	}

	/** Returns whether this cluster/pool identity has been registered. */
	public boolean isPoolRegistered(@NotNull final ResourceKey<ClusterKey, PoolKey> key) {
		final ResourcePools<PoolKey, T> cluster = findCluster(key.getClusterKey());
		return cluster != null && cluster.containsPool(key.getPoolKey());
	}

	/** Returns whether this cluster is registered. */
	public boolean isClusterRegistered(@NotNull final ClusterKey key) {
		return findCluster(key) != null;
	}

	/** Returns cluster-specific settings, or the global defaults. */
	@NotNull
	public ClusterConfig<ClusterKey, PoolKey, T> getClusterConfig(@NotNull final ClusterKey key) {
		registryLock.lock();
		try {
			return resourceClusterConfigs.getOrDefault(key, clusterConfig);
		} finally {
			registryLock.unlock();
		}
	}

	/** Selects one pool using the configured strategy, then applies the legacy configured wait timeout. No failover is added. */
	@Nullable
	public PoolableObject<T> claimResourceFromCluster(final ClusterKey key) throws InterruptedException {
		final ResourcePool<PoolKey, T> selected = findOrCreateCluster(key, null).cycle(getLoadBalancingStrategy(key), null);
		return selected.claim(getClusterConfig(key).getClaimTimeout());
	}

	/**
	 * Selects one pool and acquires a resource with optional cancellation and one total budget. The configured cluster
	 * timeout can shorten, but never extend, the caller's budget. Returns null on timeout; cancellation throws
	 * {@link java.util.concurrent.CancellationException}. A running application callback must return cooperatively.
	 *
	 * @since 4.1.0
	 */
	@Nullable
	public PoolableObject<T> claimResourceFromCluster(final ClusterKey key, final ClaimOptions options) throws InterruptedException {
		final AllocationContext context = startClaim(key, options);
		if (context == null) {
			return null;
		}
		final ResourcePools<PoolKey, T> cluster = findOrCreateCluster(key, context);
		if (cluster == null) {
			return null;
		}
		final ResourcePool<PoolKey, T> selected = cluster.cycle(getLoadBalancingStrategy(key), context);
		return selected == null || !ClaimBudget.active(context) ? null : selected.claim(context);
	}

	/** Claims from the specified pool, registering it once if needed, using the legacy configured wait timeout. */
	@Nullable
	public PoolableObject<T> claimResourceFromPool(final ResourceKey<ClusterKey, PoolKey> key) throws InterruptedException {
		final ResourcePool<PoolKey, T> pool = registeredPool(key, null);
		return pool.claim(getClusterConfig(key.getClusterKey()).getClaimTimeout());
	}

	/**
	 * Claims from the specified pool using the same cancellation and budget contract as
	 * {@link #claimResourceFromCluster(Object, ClaimOptions)}. Concurrent first callers share one registration.
	 * Once initialization starts it is pool-owned: cancelling its first caller does not delete a neighbour's pool.
	 * Acquisition cancellation ends at handoff and does not revoke a borrowed object.
	 *
	 * @since 4.1.0
	 */
	@Nullable
	public PoolableObject<T> claimResourceFromPool(final ResourceKey<ClusterKey, PoolKey> key, final ClaimOptions options)
			throws InterruptedException {
		final AllocationContext context = startClaim(key.getClusterKey(), options);
		if (context == null) {
			return null;
		}
		final ResourcePool<PoolKey, T> pool = registeredPool(key, context);
		return pool == null || !ClaimBudget.active(context) ? null : pool.claim(context);
	}

	/** Delegates to the matching-only route with the applicable cluster's configured timeout. */
	@Nullable
	public PoolableObject<T> claimMatchingResourceFromPool(@NotNull final ResourceKey<ClusterKey, PoolKey> key,
			@NotNull final Predicate<PoolableObject<T>> predicate) throws InterruptedException {
		return claimMatchingResourceFromPool(key, predicate, getClusterConfig(key.getClusterKey()).getClaimTimeout());
	}

	/** Claims an already available matching resource. Never registers a pool or allocates a resource. */
	@Nullable
	public PoolableObject<T> claimMatchingResourceFromPool(@NotNull final ResourceKey<ClusterKey, PoolKey> key,
			@NotNull final Predicate<PoolableObject<T>> predicate, @NotNull final Timeout timeout) throws InterruptedException {
		final ResourcePools<PoolKey, T> cluster = findCluster(key.getClusterKey());
		return cluster == null ? null : cluster.claimMatchingResource(key.getPoolKey(), predicate, timeout);
	}

	/**
	 * Matching-only counterpart of {@link #claimResourceFromPool(ResourceKey, ClaimOptions)}. An absent or not-yet-
	 * initialized pool stays uninitialized. Keep the predicate fast and side-effect free; it runs under pool bookkeeping.
	 *
	 * @since 4.1.0
	 */
	@Nullable
	public PoolableObject<T> claimMatchingResourceFromPool(@NotNull final ResourceKey<ClusterKey, PoolKey> key,
			@NotNull final Predicate<PoolableObject<T>> predicate, final ClaimOptions options) throws InterruptedException {
		final AllocationContext context = startClaim(key.getClusterKey(), options);
		if (context == null || !ClaimBudget.acquire(registryLock, context)) {
			return null;
		}
		final ResourcePools<PoolKey, T> cluster;
		try {
			cluster = resourceClusters.get(key.getClusterKey());
		} finally {
			registryLock.unlock();
		}
		return cluster == null || !ClaimBudget.active(context) ? null : cluster.claimMatchingResource(key.getPoolKey(), predicate, context);
	}

	/** Counts live resources, including pools whose retirement is still in progress. */
	public int countLiveResources() {
		int total = 0;
		for (final ResourcePools<PoolKey, T> cluster : clustersSnapshot()) {
			total += cluster.currentlyAllocated();
		}
		return total;
	}

	/** Delegates to {@link #shutdownPool(Object)} for all pool keys. */
	public Future<?> shutDown() {
		return shutdownPool(null);
	}

	/**
	 * Retires currently registered pools for this key (all keys when null). The future includes in-progress
	 * initialization and disposal. Later registrations are new work; the clusters remain reusable after shutdown.
	 */
	public Future<Void> shutdownPool(@Nullable final PoolKey key) {
		final List<Future<Void>> completions = new ArrayList<>();
		for (final ResourcePools<PoolKey, T> cluster : clustersSnapshot()) {
			completions.add(cluster.shutdownPool(key));
		}
		return CompositeFuturesAsFutureTask.ofFutures(completions);
	}

	private AllocationContext startClaim(final ClusterKey key, final ClaimOptions options) throws InterruptedException {
		final AllocationContext context = requireNonNull(options, "options").start();
		if (!ClaimBudget.acquire(registryLock, context)) {
			return null;
		}
		try {
			final AllocationContext limited = context.limitedTo(resourceClusterConfigs.getOrDefault(key, clusterConfig).getClaimTimeout());
			return ClaimBudget.active(limited) ? limited : null;
		} finally {
			registryLock.unlock();
		}
	}

	private ResourcePool<PoolKey, T> registeredPool(final ResourceKey<ClusterKey, PoolKey> key, final AllocationContext context)
			throws InterruptedException {
		final ResourcePools<PoolKey, T> cluster = findOrCreateCluster(key.getClusterKey(), context);
		if (cluster == null) {
			return null;
		}
		final ClusterConfig<ClusterKey, PoolKey, T> config = getClusterConfig(key.getClusterKey());
		return cluster.register(key.getPoolKey(), () -> newPool(key, config.getDefaultExpirationPolicy(),
				config.getDefaultCorePoolSize(), config.getDefaultMaxPoolSize()), false, context);
	}

	private GenericObjectPool<T> newPool(final ResourceKey<ClusterKey, PoolKey> key, final ExpirationPolicy<T> expiration,
			final int coreSize, final int maxSize) {
		return new GenericObjectPool<>(PoolConfig.<T>builder().corePoolsize(coreSize).maxPoolsize(maxSize)
				.expirationPolicy(expiration).build(), clusterConfig.getAllocatorFactory().create(key));
	}

	private ResourcePools<PoolKey, T> findOrCreateCluster(final ClusterKey key, final AllocationContext context)
			throws InterruptedException {
		if (!ClaimBudget.acquire(registryLock, context)) {
			return null;
		}
		final ResourcePools<PoolKey, T> cluster;
		try {
			if (!ClaimBudget.active(context)) {
				return null;
			}
			cluster = resourceClusters.containsKey(key) ? resourceClusters.get(key) : createCluster(key);
		} finally {
			registryLock.unlock();
		}
		return cluster.prepareSelection(context) ? cluster : null;
	}

	/** Caller holds registryLock. The strategy factory itself runs later under its separate serialization gate. */
	private ResourcePools<PoolKey, T> createCluster(final ClusterKey key) {
		final ResourcePools<PoolKey, T> cluster = new ResourcePools<>(
				() -> getLoadBalancingStrategy(key).createCollectionForCycling(), selectionLock);
		resourceClusters.put(key, cluster);
		return cluster;
	}

	private ResourcePools<PoolKey, T> findCluster(final ClusterKey key) {
		registryLock.lock();
		try {
			return resourceClusters.get(key);
		} finally {
			registryLock.unlock();
		}
	}

	private List<ResourcePools<PoolKey, T>> clustersSnapshot() {
		registryLock.lock();
		try {
			return new ArrayList<>(resourceClusters.values());
		} finally {
			registryLock.unlock();
		}
	}

	private void prepareLegacySelection(final ResourcePools<PoolKey, T> cluster) {
		try {
			cluster.prepareSelection(null);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Cluster registration interrupted", interrupted);
		}
	}

	@SuppressWarnings("unchecked")
	private LoadBalancingStrategy<ResourcePool<PoolKey, T>, Collection<ResourcePool<PoolKey, T>>> getLoadBalancingStrategy(final ClusterKey key) {
		return getClusterConfig(key).getLoadBalancingStrategy();
	}
}
