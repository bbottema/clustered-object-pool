package org.bbottema.clusteredobjectpool.core;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import org.bbottema.clusteredobjectpool.util.CompositeFuturesAsFutureTask;
import org.bbottema.genericobjectpool.PoolableObject;
import org.bbottema.genericobjectpool.util.Timeout;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Future;

/**
 * Serves to hide some methods that iterate over a cluster of pools.
 */
@RequiredArgsConstructor
@ToString
class ResourcePools<PoolKey, T> {
	@Getter(AccessLevel.PACKAGE)
	private final Collection<ResourcePool<PoolKey, T>> clusterCollection;
	private final Collection<ResourcePool<PoolKey, T>> poolsShuttingDown = new ArrayList<>();
	
	@SuppressWarnings("UnusedReturnValue")
	Future<Void> shutdownPool(@Nullable PoolKey key) {
		final List<Future<Void>> poolsShuttingDownFuture = new ArrayList<>();
		for (Iterator<ResourcePool<PoolKey, T>> iterator = clusterCollection.iterator(); iterator.hasNext(); ) {
			ResourcePool<PoolKey, T> poolInCluster = iterator.next();
			if (key == null || poolInCluster.getPoolKey().equals(key)) {
				poolsShuttingDownFuture.add(poolInCluster.clearPool());
				poolsShuttingDown.add(poolInCluster);
				iterator.remove();
			}
		}
		return CompositeFuturesAsFutureTask.ofFutures(poolsShuttingDownFuture);
	}
	
	boolean containsPool(PoolKey poolKey) {
		return findResourcePool(poolKey) != null;
	}
	
	void add(ResourcePool<PoolKey, T> resourcePool) {
		clusterCollection.add(resourcePool);
	}
	
	@Nullable
	PoolableObject<T> claimResource(PoolKey poolKey, Timeout claimTimeout) throws InterruptedException {
		ResourcePool<PoolKey, T> resourcePool = findResourcePool(poolKey);
		if (resourcePool == null) {
			throw new IllegalArgumentException("Couldn't find resource pool with key: " + poolKey);
		}
		return resourcePool.claim(claimTimeout);
	}
	
	@Nullable
	private ResourcePool<PoolKey, T> findResourcePool(PoolKey poolKey) {
		for (ResourcePool<PoolKey, T> resourcePool : clusterCollection) {
			if (resourcePool.getPoolKey().equals(poolKey)) {
				return resourcePool;
			}
		}
		return null;
	}
	
	int currentlyAllocated() {
		int total = 0;
		for (ResourcePool<PoolKey, T> resourcePool : clusterCollection) {
			total += resourcePool.getPoolMetrics().getCurrentlyAllocated();
		}
		for (ResourcePool<PoolKey, T> resourcePool : poolsShuttingDown) {
			total += resourcePool.getPoolMetrics().getCurrentlyAllocated();
		}
		return total;
	}
	
}