package org.bbottema.clusteredobjectpool.core;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Lets shutdown include a pool whose allocator factory is still running, without creating another worker. */
final class DeferredPoolShutdown implements Future<Void> {
	private final CompletableFuture<Future<Void>> disposal = new CompletableFuture<>();

	void awaitDisposal(final Future<Void> completion) {
		disposal.complete(completion);
	}

	@Override
	public boolean cancel(final boolean mayInterruptIfRunning) {
		return false;
	}

	@Override
	public boolean isCancelled() {
		return false;
	}

	@Override
	public boolean isDone() {
		return disposal.isDone() && disposal.join().isDone();
	}

	@Override
	public Void get() throws InterruptedException, ExecutionException {
		return disposal.get().get();
	}

	@Override
	public Void get(final long timeout, final TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
		final long start = System.nanoTime();
		final Future<Void> completion = disposal.get(timeout, unit);
		final long remaining = Math.max(0, unit.toNanos(timeout) - (System.nanoTime() - start));
		return completion.get(remaining, TimeUnit.NANOSECONDS);
	}
}
