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