package org.bbottema.clusteredobjectpool;

import org.bbottema.clusteredobjectpool.core.ClusterConfig;
import org.bbottema.clusteredobjectpool.core.ResourceClusters;
import org.bbottema.clusteredobjectpool.core.api.AllocatorFactory;
import org.bbottema.clusteredobjectpool.core.api.ResourceKey;
import org.bbottema.clusteredobjectpool.core.api.ResourceKey.ResourceClusterAndPoolKey;
import org.bbottema.genericobjectpool.Allocator;
import org.bbottema.genericobjectpool.PoolableObject;
import org.bbottema.genericobjectpool.expirypolicies.TimeoutSinceCreationExpirationPolicy;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

class ClusterAwareAllocatorFactoryTest {

    @Test
    void createAllocatorCanUseClusterAndPoolKeys() throws InterruptedException {
        final UUID clusterKey = UUID.randomUUID();
        final ResourceClusters<UUID, String, String> clusters = new ResourceClusters<>(ClusterConfig.<UUID, String, String>builder()
                .allocatorFactory(new AllocatorFactory<UUID, String, String>() {
                    @Override
                    public Allocator<String> create(@NotNull final ResourceKey<UUID, String> key) {
                        return new Allocator<String>() {
                            @NotNull
                            @Override
                            public String allocate() {
                                return key.getClusterKey() + "::" + key.getPoolKey();
                            }

                            @Override
                            public void deallocate(final String object) {
                            }
                        };
                    }
                })
                .defaultExpirationPolicy(new TimeoutSinceCreationExpirationPolicy<>(10, SECONDS))
                .defaultMaxPoolSize(1)
                .build());

        final PoolableObject<String> resource = clusters.claimResourceFromPool(new ResourceClusterAndPoolKey<>(clusterKey, "server_A"));

        assertThat(requireNonNull(resource).getAllocatedObject()).isEqualTo(clusterKey + "::server_A");
        resource.release();
    }
}
