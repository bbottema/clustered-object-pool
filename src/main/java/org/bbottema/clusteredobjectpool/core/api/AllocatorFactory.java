package org.bbottema.clusteredobjectpool.core.api;

import org.bbottema.genericobjectpool.Allocator;
import org.jetbrains.annotations.NotNull;

/**
 * Allows you to create an Allocator for a cluster and pool key combination, invoked each time generic-object-pool expands the object pool.
 *
 * @param <ClusterKey> See {@link org.bbottema.clusteredobjectpool.core.ResourceClusters}.
 * @param <PoolKey> See {@link org.bbottema.clusteredobjectpool.core.ResourceClusters}.
 * @param <T>       See {@link org.bbottema.clusteredobjectpool.core.ResourceClusters}.
 */
public interface AllocatorFactory<ClusterKey, PoolKey, T> {
    /**
     * @see AllocatorFactory
     */
    @NotNull
    Allocator<T> create(@NotNull ResourceKey<ClusterKey, PoolKey> resourceKey);
}
