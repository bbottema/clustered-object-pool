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
package org.bbottema.clusteredobjectpool;

import org.bbottema.clusteredobjectpool.core.ClusterConfig;
import org.bbottema.clusteredobjectpool.core.ResourceClusters;
import org.bbottema.clusteredobjectpool.core.api.AllocatorFactory;
import org.bbottema.genericobjectpool.ExpirationPolicy;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

class TestableDummyClustersDefaultAutoSizing extends ResourceClusters<UUID, String, String> {
    TestableDummyClustersDefaultAutoSizing(@NotNull final AllocatorFactory<String, String> allocatorFactory,
                                           @NotNull final ExpirationPolicy<String> defaultExpiration) {
        super(ClusterConfig.<String, String>builder()
                .allocatorFactory(allocatorFactory)
                .defaultExpirationPolicy(defaultExpiration)
                .defaultCorePoolSize(4)
                .defaultMaxPoolSize(4)
                .build());
    }
}