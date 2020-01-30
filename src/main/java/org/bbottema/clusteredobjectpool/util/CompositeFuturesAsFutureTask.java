package org.bbottema.clusteredobjectpool.util;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

import static java.util.concurrent.Executors.defaultThreadFactory;
import static java.util.concurrent.Executors.newSingleThreadExecutor;

public class CompositeFuturesAsFutureTask extends FutureTask<Void> {

	public static Future<Void> ofFutures(final List<Future<Void>> futures) {
		ExecutorService executorService = newSingleThreadExecutor(defaultThreadFactory());
		Future<Void> future = executorService.submit(new CompositeFuturesAsFutureTask(futures), null);
		executorService.shutdown();
		return future;
	}

	private CompositeFuturesAsFutureTask(final List<Future<Void>> futures) {
		super(new Callable<Void>() {
			@Override
			public Void call() throws ExecutionException, InterruptedException {
				for (Future<Void> future : futures) {
					future.get();
				}
				return null;
			}
		});
	}
}