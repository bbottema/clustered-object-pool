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
package org.bbottema.clusteredobjectpool.core;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Value;
import org.bbottema.clusteredobjectpool.core.api.AllocatorFactory;
import org.bbottema.clusteredobjectpool.core.api.CyclingStrategy;
import org.bbottema.clusteredobjectpool.cyclingstrategies.RoundRobinCyclingStrategy;
import org.bbottema.genericobjectpool.ExpirationPolicy;
import org.bbottema.genericobjectpool.util.ForeverTimeout;
import org.bbottema.genericobjectpool.util.Timeout;
import org.jetbrains.annotations.NotNull;

/**
 * @param <PoolKey> See {@link ResourceClusters}.
 * @param <T>       See {@link ResourceClusters}.
 */
@Value
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuppressFBWarnings(justification = "Generated code")
public class ClusterConfig<PoolKey, T> {
    
    /**
     * Produces allocators for when generic-object-pool expands an object pool. For example to obtains a new {@code Transport} connection for {@code PoolKey} Session.
     */
    @NotNull private final AllocatorFactory<PoolKey, T> allocatorFactory;
    /**
     * Default expiration policy (generic-object-pool parameter) to use if a specific one is not provided when registering a new resource pool.
     */
    @NotNull private final ExpirationPolicy<T> defaultExpirationPolicy;
    /**
     * Default max resource pool size (generic-object-pool parameter) to use if a specific one is not provided when registering a new resource pool.
     * <p>
     * Defaults to {@value}.
     */
    @Builder.Default
    private final int defaultMaxPoolSize = 0;
    /**
     * Default core resource pool size to use if a specific one is not provided when registering a new resource pool.
     * <p>
     * Defaults to {@value}.
     */
    @Builder.Default
    private final int defaultCorePoolSize = 0;
    /**
     * Global claim timeout (generic-object-pool parameter). Dictates how long the pool manager waits before giving up. <br> Default is waiting forever.
     */
    @Builder.Default
    @NotNull private final Timeout claimTimeout = ForeverTimeout.WAIT_FOREVER;
    /**
     *  Strategy for choosing which resource pool (for example connections to a specific server) in a cluster (of servers) to utilize.
     *  Defaults to {@link RoundRobinCyclingStrategy}.
     */
    @Builder.Default
    @NotNull private final CyclingStrategy cyclingStrategy = new RoundRobinCyclingStrategy();
}