package org.bbottema.clusteredobjectpool.core;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.Value;
import org.bbottema.genericobjectpool.GenericObjectPool;
import org.bbottema.genericobjectpool.PoolMetrics;
import org.bbottema.genericobjectpool.PoolableObject;
import org.bbottema.genericobjectpool.util.Timeout;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.Future;

/**
 * Serves to keep track of the poolKey associated with the generic-object-pool pool.
 */
@Value
@SuppressFBWarnings(justification = "Generated code")
class ResourcePool<PoolKey, T> {
	final PoolKey poolKey;
	final GenericObjectPool<T> pool;
	
	Future<?> clearPool() {
		return pool.shutdown();
	}
	
	@Nullable
	PoolableObject<T> claim(Timeout claimTimeout) throws InterruptedException {
		return pool.claim(claimTimeout);
	}
	
	@NotNull
	PoolMetrics getPoolMetrics() {
		return pool.getPoolMetrics();
	}
}