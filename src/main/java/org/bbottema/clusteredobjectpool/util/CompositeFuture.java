package org.bbottema.clusteredobjectpool.util;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

public class CompositeFuture extends FutureTask<Void> {
	public CompositeFuture(final List<Future> future) {
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
