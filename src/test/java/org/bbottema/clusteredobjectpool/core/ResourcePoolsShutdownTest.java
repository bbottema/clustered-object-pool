package org.bbottema.clusteredobjectpool.core;

import org.bbottema.genericobjectpool.Allocator;
import org.bbottema.genericobjectpool.GenericObjectPool;
import org.bbottema.genericobjectpool.PoolConfig;
import org.bbottema.genericobjectpool.PoolableObject;
import org.bbottema.genericobjectpool.util.Timeout;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResourcePoolsShutdownTest {

	@Test
	void forgetsRetiredPoolOnlyAfterItsDeallocationFinishes() throws Exception {
		CountDownLatch deallocationStarted = new CountDownLatch(1);
		CountDownLatch allowDeallocationToFinish = new CountDownLatch(1);
		GenericObjectPool<Boolean> objectPool = new GenericObjectPool<>(
				PoolConfig.<Boolean>builder().maxPoolsize(1).build(),
				new Allocator<Boolean>() {
					@NotNull
					@Override
					public Boolean allocate() {
						return true;
					}

					@Override
					public void deallocate(Boolean object) {
						deallocationStarted.countDown();
						try {
							allowDeallocationToFinish.await();
						} catch (InterruptedException e) {
							Thread.currentThread().interrupt();
						}
					}
				});
		ResourcePools<String, Boolean> pools = new ResourcePools<>(new ArrayList<ResourcePool<String, Boolean>>());
		pools.add(new ResourcePool<>("credential-generation", objectPool));
		PoolableObject<Boolean> lease = pools.claimResource("credential-generation", new Timeout(1, TimeUnit.SECONDS));
		assertThat(pools.retireIfEmpty()).isFalse();

		try {
			Future<Void> shutdown = pools.shutdownPool("credential-generation");
			Future<Void> aggregateShutdown = pools.shutdownPool(null);
			assertThat(pools.trackedShuttingDownPoolCount()).isOne();
			assertThat(aggregateShutdown.isDone()).isFalse();

			lease.release();
			assertThat(deallocationStarted.await(1, TimeUnit.SECONDS)).isTrue();
			assertThat(shutdown.isDone()).isFalse();
			assertThat(pools.retireIfEmpty()).isFalse();

			allowDeallocationToFinish.countDown();
			shutdown.get(1, TimeUnit.SECONDS);
			aggregateShutdown.get(1, TimeUnit.SECONDS);
			assertThat(pools.trackedShuttingDownPoolCount()).isZero();
			assertThat(pools.retireIfEmpty()).isTrue();
			assertThatThrownBy(() -> pools.register("late", () -> {
				throw new AssertionError("Removed clusters must not create another pool");
			}, true, null)).isInstanceOf(IllegalStateException.class).hasMessageContaining("removed");
		} finally {
			allowDeallocationToFinish.countDown();
		}
	}
}
