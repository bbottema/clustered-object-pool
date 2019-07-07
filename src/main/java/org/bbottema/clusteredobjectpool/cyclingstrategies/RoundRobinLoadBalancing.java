/*
 * Copyright (C) 2019 Benny Bottema (benny@bennybottema.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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