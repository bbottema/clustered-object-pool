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
import lombok.Value;
import org.bbottema.genericobjectpool.GenericObjectPool;
import org.bbottema.genericobjectpool.PoolMetrics;
import org.bbottema.genericobjectpool.PoolableObject;
import org.bbottema.genericobjectpool.util.Timeout;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Serves to keep track of the poolKey associated with the generic-object-pool pool.
 */
@Value
@SuppressFBWarnings(justification = "Generated code")
class ResourcePool<PoolKey, T> {
	final PoolKey poolKey;
	final GenericObjectPool<T> pool;
	
	void clearPool() {
		pool.shutdown();
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