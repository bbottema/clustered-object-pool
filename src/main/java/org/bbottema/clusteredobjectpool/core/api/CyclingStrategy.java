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
package org.bbottema.clusteredobjectpool.core.api;

import org.jetbrains.annotations.NotNull;

import java.util.Collection;

/**
 * Strategy for choosing which resource pool (for example connections to a specific server)
 * in a cluster (of servers) to utilize.
 */
public interface CyclingStrategy<C extends Collection> {
    /**
     * @return Any collection that best supports this cycling strategy. <br>
     * For example a LinkedList to return a Queue, used return items in a FIFO fashion.
     */
    @NotNull C createCollectionForCycling();

    /**
     * @return The next item in the given collection, according to this strategy.
     */
    @NotNull <T> T cycle(@NotNull C items);
}