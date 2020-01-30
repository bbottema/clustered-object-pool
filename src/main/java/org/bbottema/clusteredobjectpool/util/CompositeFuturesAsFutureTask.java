package org.bbottema.clusteredobjectpool.util;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

import static java.util.concurrent.Executors.defaultThreadFactory;
import static java.util.concurrent.Executors.newSingleThreadExecutor;

public class CompositeFuturesAsFutureTask extends FutureTask<Void> {

	public static Future<Void> ofFutures(final List<Future<Void>> futures) {
		return newSingleThreadExecutor(defaultThreadFactory())
				.submit(new CompositeFuturesAsFutureTask(futures), null);
	}

	private CompositeFuturesAsFutureTask(final List<Future<Void>> future) {
		super(new Callable<Void>() {
			@Override
			public Void call() throws ExecutionException, InterruptedException {
				while (!future.isEmpty()) {
					future.iterator().next().get();
				}
				return null;
			}
		});
	}
}