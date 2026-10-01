package org.bbottema.clusteredobjectpool.core;

import org.bbottema.genericobjectpool.AllocationContext;
import org.bbottema.genericobjectpool.ClaimOptions;
import org.bbottema.genericobjectpool.PoolableObject;
import org.bbottema.genericobjectpool.util.Timeout;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static java.util.Objects.requireNonNull;

/**
 * A selected pool registration, not a borrowed resource. See {@link ResourceClusters#selectPoolFromCluster(Object)}
 * for when to select a destination before borrowing from it.
 * Holding this object does not reserve capacity or keep the pool alive; there is nothing to close or release.
 * Retirement makes later claims fail rather than silently using a replacement registered with the same key.
 *
 * @param <PoolKey> identity of the selected pool
 * @param <T> resource produced by that pool
 */
public final class ResourcePoolSelection<PoolKey, T> {

	private final ResourcePool<PoolKey, T> pool;
	private final Timeout claimTimeout;

	ResourcePoolSelection(final ResourcePool<PoolKey, T> pool, final Timeout claimTimeout) {
		this.pool = pool;
		this.claimTimeout = claimTimeout;
	}

	/** Returns the original key, without initializing the pool or allocating a resource. */
	public PoolKey getPoolKey() {
		return pool.getPoolKey();
	}

	/** Claims from this registration with its configured timeout; selection is not repeated. */
	@Nullable
	public PoolableObject<T> claim() throws InterruptedException {
		return pool.claim(claimTimeout);
	}

	/**
	 * Claims from this registration with a fresh acquisition budget, capped by the cluster's configured timeout.
	 * Time spent selecting or doing application work between selection and this call is not part of that budget.
	 * Supply your remaining time here when selection and acquisition must fit one application deadline.
	 * Returns null on timeout; cancellation throws {@link java.util.concurrent.CancellationException}.
	 * Cancellation affects this claim only and does not revoke a resource already handed to its caller.
	 */
	@Nullable
	public PoolableObject<T> claim(@NotNull final ClaimOptions options) throws InterruptedException {
		final AllocationContext context = requireNonNull(options, "options").start().limitedTo(claimTimeout);
		return ClaimBudget.active(context) ? pool.claim(context) : null;
	}
}
