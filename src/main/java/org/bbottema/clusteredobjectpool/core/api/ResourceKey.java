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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.RequiredArgsConstructor;
import lombok.Value;

/**
 * Defines the cluster + pool identity for registering clusters or claiming resources from clusters.
 * <p>
 * There are two keys depending if you need clustering or just a single resource pool.
 * <ul>
 *    <li>{@link ResourceClusterAndPoolKey}: Can be used to register and claim resources <em>with</em> clustering.</li>
 *    <li>{@link ResourcePoolKey}: Can be used to register and claim resources <em>without</em> clustering.
 *        Technically a cluster of one, where the key functions as both the cluster and resource pool key.</li>
 * </ul>
 *
 * @param <ClusterKey> See {@link org.bbottema.clusteredobjectpool.core.ResourceClusters}.
 * @param <PoolKey>    See {@link org.bbottema.clusteredobjectpool.core.ResourceClusters}.
 */
public interface ResourceKey<ClusterKey, PoolKey> {

    ClusterKey getClusterKey();

    PoolKey getPoolKey();

    /**
     * Can be used to register and claim resources <em>with</em> clustering.
     *
     * @see ResourceKey
     */
    @RequiredArgsConstructor
    @Value
    @SuppressFBWarnings(justification = "Generated code")
    final class ResourceClusterAndPoolKey<ClusterKey, PoolKey> implements ResourceKey<ClusterKey, PoolKey> {
        private final ClusterKey clusterKey;
        private final PoolKey poolKey;
    }

    /**
     * Can be used to register and claim resources <em>without</em> clustering.
     * <p>
     * Technically a cluster of one, where the key functions as both the cluster and resource pool key.
     *
     * @see ResourceKey
     */
    @Value
    @SuppressFBWarnings(justification = "Generated code")
    final class ResourcePoolKey<PoolKey> implements ResourceKey<PoolKey, PoolKey> {
        private final PoolKey clusterKey;
        private final PoolKey poolKey;

        public ResourcePoolKey(PoolKey key) {
            this.clusterKey = key;
            this.poolKey = key;
        }
    }
}