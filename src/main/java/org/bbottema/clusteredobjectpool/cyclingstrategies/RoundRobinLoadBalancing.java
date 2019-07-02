package org.bbottema.clusteredobjectpool.cyclingstrategies;

import org.bbottema.clusteredobjectpool.core.api.LoadBalancingStrategy;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedList;
import java.util.Queue;

/**
 * Evenly cycles through the given items.
 */
@SuppressWarnings("unused")
public class RoundRobinLoadBalancing<T> implements LoadBalancingStrategy<T, Queue<T>> {
	
	@NotNull
	@Override
	public Queue<T> createCollectionForCycling() {
		return new LinkedList<>();
	}
	
	@NotNull
	@Override
	public T cycle(@NotNull Queue<T> items) {
		T nextItem = items.remove();
		items.add(nextItem);
		return nextItem;
	}
}