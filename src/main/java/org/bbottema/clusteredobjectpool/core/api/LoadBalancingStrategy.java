package org.bbottema.clusteredobjectpool.core.api;

import org.jetbrains.annotations.NotNull;

import java.util.Collection;

/**
 * Strategy for choosing which resource pool (for example connections to a specific server)
 * in a cluster (of servers) to utilize.
 */
public interface LoadBalancingStrategy<T, C extends Collection<T>> {
    /**
     * @return Any collection that best supports this cycling strategy. <br>
     * For example a LinkedList to return a Queue, used return items in a FIFO fashion.
     */
    @NotNull C createCollectionForCycling();

    /**
     * @return The next item in the given collection, according to this strategy.
     */
    @NotNull T cycle(@NotNull C items);
}