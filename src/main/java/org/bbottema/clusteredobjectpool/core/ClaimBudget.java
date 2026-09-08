package org.bbottema.clusteredobjectpool.core;

import org.bbottema.genericobjectpool.AllocationContext;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;

/** Applies an optional running budget to library-owned registry and callback-gate waits. */
final class ClaimBudget {
	private ClaimBudget() {
	}

	static boolean active(final AllocationContext context) throws InterruptedException {
		if (context == null) {
			return true;
		}
		context.throwIfCancellationRequested();
		if (Thread.interrupted()) {
			throw new InterruptedException("Cluster acquisition interrupted");
		}
		return !context.isTimedOut();
	}

	static boolean acquire(final Lock lock, final AllocationContext context) throws InterruptedException {
		if (context == null) {
			lock.lock();
			return true;
		}
		while (active(context)) {
			final long wait = Math.min(TimeUnit.MILLISECONDS.toNanos(10), context.getRemainingTime(TimeUnit.NANOSECONDS));
			if (lock.tryLock(wait, TimeUnit.NANOSECONDS)) {
				return true;
			}
		}
		return false;
	}
}
