package org.bbottema.clusteredobjectpool.util;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

import static java.util.concurrent.Executors.defaultThreadFactory;
import static java.util.concurrent.Executors.newSingleThreadExecutor;

public class CompositeFuturesAsFutureTask extends FutureTask<Void> {

	public static Future<Void> ofFutures(final List<Future<Void>> futures) {
		return ofFutures(futures, new Runnable() {
			@Override
			public void run() {
				// no-op
			}
		});
	}

	public static Future<Void> ofFutures(final List<Future<Void>> futures, final Runnable completion) {
		ExecutorService executorService = newSingleThreadExecutor(defaultThreadFactory());
		final CompositeFuturesAsFutureTask future = new CompositeFuturesAsFutureTask(futures, completion);
		executorService.execute(future);
		executorService.shutdown();
		return future;
	}

	private CompositeFuturesAsFutureTask(final List<Future<Void>> futures, final Runnable completion) {
		super(new Callable<Void>() {
			@Override
			public Void call() throws ExecutionException, InterruptedException {
				final ExecutionException failure = awaitEveryCompletion(futures);
				try {
					completion.run();
				} catch (RuntimeException cleanupFailure) {
					if (failure == null) {
						throw cleanupFailure;
					}
					failure.addSuppressed(cleanupFailure);
				}
				if (failure != null) {
					throw failure;
				}
				return null;
			}
		});
	}

	/** A failed pool must not make shutdown forget other pools whose cleanup is still running. */
	private static ExecutionException awaitEveryCompletion(final List<Future<Void>> futures) throws InterruptedException {
		ExecutionException firstFailure = null;
		for (final Future<Void> future : futures) {
			try {
				future.get();
			} catch (ExecutionException | CancellationException failure) {
				if (firstFailure == null) {
					firstFailure = failure instanceof ExecutionException ? (ExecutionException) failure : new ExecutionException(failure);
				} else if (failure != firstFailure) {
					firstFailure.addSuppressed(failure);
				}
			}
		}
		return firstFailure;
	}
}
