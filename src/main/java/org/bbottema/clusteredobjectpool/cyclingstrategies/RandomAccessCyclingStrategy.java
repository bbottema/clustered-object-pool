package org.bbottema.clusteredobjectpool.cyclingstrategies;

import org.bbottema.clusteredobjectpool.core.api.CyclingStrategy;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Randomly (thread-safe) cycles through the given items.
 */
@SuppressWarnings("unused")
public class RandomAccessCyclingStrategy implements CyclingStrategy<List> {
	
	@NotNull
	@Override
	public List createCollectionForCycling() {
		return new LinkedList<>();
	}
	
	@NotNull
	@Override
	public <T> T cycle(@NotNull List items) {
		@SuppressWarnings({"unchecked"}) List<T> itemsTyped = items;
		return itemsTyped.get(ThreadLocalRandom.current().nextInt(items.size()) % items.size());
	}
}