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
		return claimWithContext(requireNonNull(options, "options").start());
	}

	/**
	 * Continues a claim that another integration layer already started, without changing its identity or restarting
	 * its timeout. The configured pool timeout can only shorten the budget. Selection stays pinned to this original
	 * registration, including when another pool has since been registered with the same key.
	 *
	 * @return the borrowed resource, or {@code null} when the remaining budget expires
	 * @since 4.3.0
	 */
	@Nullable
	public PoolableObject<T> claimWithContext(@NotNull final AllocationContext context) throws InterruptedException {
		final AllocationContext limited = requireNonNull(context, "context").limitedTo(claimTimeout);
		return ClaimBudget.active(limited) ? pool.claim(limited) : null;
	}
}
