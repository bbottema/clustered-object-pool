package org.bbottema.clusteredobjectpool.core;

import org.bbottema.genericobjectpool.AllocationContext;
import org.bbottema.genericobjectpool.GenericObjectPool;
import org.bbottema.genericobjectpool.PoolableObject;
import org.bbottema.genericobjectpool.util.Timeout;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** One shared registration: its factory runs outside registry locks, and retirement includes late initialization. */
final class ResourcePool<PoolKey, T> {
	private final PoolKey poolKey;
	private final Supplier<GenericObjectPool<T>> factory;
	private final Lock initializationLock = new ReentrantLock();
	private final DeferredPoolShutdown shutdown = new DeferredPoolShutdown();
	private volatile GenericObjectPool<T> pool;
	private volatile boolean retired;
	private boolean initializing;

	ResourcePool(final PoolKey poolKey, final GenericObjectPool<T> pool) {
		this(poolKey, () -> pool);
		this.pool = pool;
	}

	ResourcePool(final PoolKey poolKey, final Supplier<GenericObjectPool<T>> factory) {
		this.poolKey = poolKey;
		this.factory = factory;
	}

	PoolKey getPoolKey() {
		return poolKey;
	}

	boolean isRetired() {
		return retired;
	}

	GenericObjectPool<T> initialize(final AllocationContext context) throws InterruptedException {
		if (!ClaimBudget.acquire(initializationLock, context)) {
			return null;
		}
		try {
			if (!ClaimBudget.active(context)) {
				return null;
			}
			if (!beginInitialization()) {
				return pool;
			}
			GenericObjectPool<T> created = null;
			try {
				created = factory.get();
				return created;
			} finally {
				finishInitialization(created);
			}
		} finally {
			initializationLock.unlock();
		}
	}

	private synchronized boolean beginInitialization() {
		if (retired) {
			throw new IllegalStateException("Pool retired during registration: " + poolKey);
		}
		if (pool != null) {
			return false;
		}
		initializing = true;
		return true;
	}

	private synchronized void finishInitialization(final GenericObjectPool<T> created) {
		pool = created;
		initializing = false;
		if (created == null) {
			retired = true;
		}
		if (retired) {
			shutdown.awaitDisposal(created == null ? CompletableFuture.completedFuture(null) : created.shutdown());
		}
	}

	synchronized Future<Void> markRetired() {
		retired = true;
		return shutdown;
	}

	synchronized Future<Void> clearPool() {
		markRetired();
		if (!initializing) {
			shutdown.awaitDisposal(pool == null ? CompletableFuture.completedFuture(null) : pool.shutdown());
		}
		return shutdown;
	}

	PoolableObject<T> claim(final Timeout timeout) throws InterruptedException {
		return initialize(null).claim(timeout);
	}

	PoolableObject<T> claim(final AllocationContext context) throws InterruptedException {
		final GenericObjectPool<T> initialized = initialize(context);
		return initialized == null || !ClaimBudget.active(context) ? null : initialized.claimWithContext(context);
	}

	PoolableObject<T> claimMatching(final Predicate<PoolableObject<T>> predicate, final Timeout timeout) throws InterruptedException {
		// Matching must not cause a not-yet-initialized registration to allocate.
		final GenericObjectPool<T> initialized = pool;
		return initialized == null ? null : initialized.claimMatching(predicate, timeout);
	}

	PoolableObject<T> claimMatching(final Predicate<PoolableObject<T>> predicate, final AllocationContext context) throws InterruptedException {
		final GenericObjectPool<T> initialized = pool;
		return initialized == null || !ClaimBudget.active(context) ? null : initialized.claimMatchingWithContext(predicate, context);
	}

	int currentlyAllocated() {
		final GenericObjectPool<T> initialized = pool;
		return initialized == null ? 0 : initialized.getCurrentlyAllocated();
	}
}
