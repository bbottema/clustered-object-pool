package org.bbottema.clusteredobjectpool.cyclingstrategies;

import org.bbottema.clusteredobjectpool.core.api.LoadBalancingStrategy;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Randomly (thread-safe) cycles through the given items.
 */
@SuppressWarnings("unused")
public class RandomAccessLoadBalancing<T> implements LoadBalancingStrategy<T, List<T>> {
	
	@NotNull
	@Override
	public List<T> createCollectionForCycling() {
		return new LinkedList<>();
	}
	
	@NotNull
	@Override
	public T cycle(@NotNull List<T> items) {
		return items.get(ThreadLocalRandom.current().nextInt(items.size()) % items.size());
	}
}