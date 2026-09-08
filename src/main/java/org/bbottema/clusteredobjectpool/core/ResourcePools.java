package org.bbottema.clusteredobjectpool.core;

import org.bbottema.clusteredobjectpool.core.api.LoadBalancingStrategy;
import org.bbottema.clusteredobjectpool.util.CompositeFuturesAsFutureTask;
import org.bbottema.genericobjectpool.AllocationContext;
import org.bbottema.genericobjectpool.GenericObjectPool;
import org.bbottema.genericobjectpool.PoolableObject;
import org.bbottema.genericobjectpool.util.Timeout;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Registry bookkeeping stays independent of serialized load-balancing callbacks and per-pool initialization. */
final class ResourcePools<PoolKey, T> {
	private final Lock registryLock = new ReentrantLock();
	private final Lock selectionLock;
	private final Supplier<Collection<ResourcePool<PoolKey, T>>> collectionFactory;
	private volatile Collection<ResourcePool<PoolKey, T>> clusterCollection;
	private final Map<PoolKey, ResourcePool<PoolKey, T>> pools = new LinkedHashMap<>();
	private final Map<ResourcePool<PoolKey, T>, Future<Void>> poolsShuttingDown = new ConcurrentHashMap<>();

	ResourcePools(final Collection<ResourcePool<PoolKey, T>> collection) {
		this(() -> collection, new ReentrantLock());
		clusterCollection = collection;
		for (final ResourcePool<PoolKey, T> pool : collection) {
			pools.put(pool.getPoolKey(), pool);
		}
	}

	ResourcePools(final Supplier<Collection<ResourcePool<PoolKey, T>>> collectionFactory, final Lock selectionLock) {
		this.collectionFactory = collectionFactory;
		this.selectionLock = selectionLock;
	}

	boolean prepareSelection(final AllocationContext context) throws InterruptedException {
		if (clusterCollection != null) {
			return ClaimBudget.active(context);
		}
		if (!ClaimBudget.acquire(selectionLock, context)) {
			return false;
		}
		try {
			if (!ClaimBudget.active(context)) {
				return false;
			}
			if (clusterCollection == null) {
				clusterCollection = collectionFactory.get();
			}
			return ClaimBudget.active(context);
		} finally {
			selectionLock.unlock();
		}
	}

	ResourcePool<PoolKey, T> cycle(final LoadBalancingStrategy<ResourcePool<PoolKey, T>, Collection<ResourcePool<PoolKey, T>>> strategy,
			final AllocationContext context) throws InterruptedException {
		if (!prepareSelection(context) || !ClaimBudget.acquire(selectionLock, context)) {
			return null;
		}
		try {
			if (!ClaimBudget.active(context)) {
				return null;
			}
			reconcileSelection();
			if (clusterCollection.isEmpty()) {
				throw new IllegalStateException("Cluster contains no pools to draw from");
			}
			return strategy.cycle(clusterCollection);
		} finally {
			selectionLock.unlock();
		}
	}

	private void reconcileSelection() {
		final List<ResourcePool<PoolKey, T>> registered = snapshot();
		clusterCollection.removeIf(pool -> !registered.contains(pool));
		for (final ResourcePool<PoolKey, T> pool : registered) {
			if (!clusterCollection.contains(pool)) {
				clusterCollection.add(pool);
			}
		}
	}

	ResourcePool<PoolKey, T> register(final PoolKey key, final Supplier<GenericObjectPool<T>> factory,
			final boolean failIfExists, final AllocationContext context) throws InterruptedException {
		if (!ClaimBudget.acquire(registryLock, context)) {
			return null;
		}
		final ResourcePool<PoolKey, T> registration;
		try {
			if (!ClaimBudget.active(context)) {
				return null;
			}
			final ResourcePool<PoolKey, T> existing = pools.get(key);
			if (existing != null && failIfExists) {
				throw new IllegalArgumentException("Pool already exists for " + key);
			}
			registration = existing == null ? new ResourcePool<>(key, factory) : existing;
			pools.put(key, registration);
		} finally {
			registryLock.unlock();
		}
		try {
			registration.initialize(context);
			return registration;
		} finally {
			if (registration.isRetired()) {
				registryLock.lock();
				try {
					pools.remove(key, registration);
				} finally {
					registryLock.unlock();
				}
			}
		}
	}

	Future<Void> shutdownPool(final PoolKey key) {
		final List<Future<Void>> completions = new ArrayList<>();
		final List<ResourcePool<PoolKey, T>> retired = new ArrayList<>();
		registryLock.lock();
		try {
			for (final Map.Entry<ResourcePool<PoolKey, T>, Future<Void>> entry : poolsShuttingDown.entrySet()) {
				if (key == null || entry.getKey().getPoolKey().equals(key)) {
					completions.add(entry.getValue());
				}
			}
			pools.entrySet().removeIf(entry -> {
				if (key != null && !entry.getKey().equals(key)) {
					return false;
				}
				final Future<Void> completion = entry.getValue().markRetired();
				poolsShuttingDown.put(entry.getValue(), completion);
				completions.add(completion);
				retired.add(entry.getValue());
				return true;
			});
		} finally {
			registryLock.unlock();
		}
		// Generic shutdown may wait for its own bookkeeping; never hold the cluster registry across that boundary.
		for (final ResourcePool<PoolKey, T> pool : retired) {
			pool.clearPool();
		}
		return CompositeFuturesAsFutureTask.ofFutures(completions, () -> {
			for (final ResourcePool<PoolKey, T> pool : retired) {
				poolsShuttingDown.remove(pool);
			}
			// Do not wait behind a caller's load-balancer callback just to forget retired collection entries.
			if (selectionLock.tryLock()) {
				try {
					if (clusterCollection != null) {
						reconcileSelection();
					}
				} finally {
					selectionLock.unlock();
				}
			}
		});
	}

	int trackedShuttingDownPoolCount() {
		return poolsShuttingDown.size();
	}

	boolean containsPool(final PoolKey key) {
		return findResourcePool(key) != null;
	}

	void add(final ResourcePool<PoolKey, T> pool) {
		registryLock.lock();
		try {
			pools.put(pool.getPoolKey(), pool);
		} finally {
			registryLock.unlock();
		}
	}

	PoolableObject<T> claimResource(final PoolKey key, final Timeout timeout) throws InterruptedException {
		final ResourcePool<PoolKey, T> pool = findResourcePool(key);
		if (pool == null) {
			throw new IllegalArgumentException("Couldn't find resource pool with key: " + key);
		}
		return pool.claim(timeout);
	}

	PoolableObject<T> claimMatchingResource(final PoolKey key, final Predicate<PoolableObject<T>> predicate, final Timeout timeout)
			throws InterruptedException {
		final ResourcePool<PoolKey, T> pool = findResourcePool(key);
		return pool == null ? null : pool.claimMatching(predicate, timeout);
	}

	PoolableObject<T> claimMatchingResource(final PoolKey key, final Predicate<PoolableObject<T>> predicate, final AllocationContext context)
			throws InterruptedException {
		if (!ClaimBudget.acquire(registryLock, context)) {
			return null;
		}
		final ResourcePool<PoolKey, T> pool;
		try {
			pool = pools.get(key);
		} finally {
			registryLock.unlock();
		}
		return pool == null ? null : pool.claimMatching(predicate, context);
	}

	private ResourcePool<PoolKey, T> findResourcePool(final PoolKey key) {
		registryLock.lock();
		try {
			return pools.get(key);
		} finally {
			registryLock.unlock();
		}
	}

	private List<ResourcePool<PoolKey, T>> snapshot() {
		registryLock.lock();
		try {
			return new ArrayList<>(pools.values());
		} finally {
			registryLock.unlock();
		}
	}

	int currentlyAllocated() {
		final List<ResourcePool<PoolKey, T>> all;
		registryLock.lock();
		try {
			all = new ArrayList<>(pools.values());
			all.addAll(poolsShuttingDown.keySet());
		} finally {
			registryLock.unlock();
		}
		int total = 0;
		for (final ResourcePool<PoolKey, T> pool : all) {
			total += pool.currentlyAllocated();
		}
		return total;
	}
}
