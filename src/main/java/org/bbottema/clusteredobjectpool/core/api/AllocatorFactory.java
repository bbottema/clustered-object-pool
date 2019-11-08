package org.bbottema.clusteredobjectpool.core.api;

import org.bbottema.genericobjectpool.Allocator;
import org.jetbrains.annotations.NotNull;

/**
 * Allows you to create an Allocator for PoolKey, invoked each time generic-object-pool expands the object pool.
 *
 * @param <PoolKey> See {@link org.bbottema.clusteredobjectpool.core.ResourceClusters}.
 * @param <T>       See {@link org.bbottema.clusteredobjectpool.core.ResourceClusters}.
 */
public interface AllocatorFactory<PoolKey, T> {
    /**
     * @see AllocatorFactory
     */
    @NotNull
    Allocator<T> create(@NotNull PoolKey poolKey);
}