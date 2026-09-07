package org.bbottema.clusteredobjectpool;

import org.bbottema.clusteredobjectpool.core.ClusterConfig;
import org.bbottema.clusteredobjectpool.core.ResourceClusters;
import org.bbottema.clusteredobjectpool.core.api.ResourceKey.ResourceClusterAndPoolKey;
import org.bbottema.genericobjectpool.Allocator;
import org.bbottema.genericobjectpool.PoolableObject;
import org.bbottema.genericobjectpool.util.Timeout;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

class ClaimRecoveryTest {

	@ParameterizedTest
	@CsvSource({"0,false", "0,true", "1,false", "1,true"})
	void invalidationWakesAnExistingKeyedOrClusteredClaim(final int coreSize, final boolean selectFromCluster) throws Exception {
		final AtomicInteger allocated = new AtomicInteger();
		final ResourceClusters<String, String, Integer> clusters = new ResourceClusters<>(
				ClusterConfig.<String, String, Integer>builder()
						.allocatorFactory(key -> new Allocator<Integer>() {
							@NotNull
							@Override
							public Integer allocate() {
								return allocated.incrementAndGet();
							}
						})
						.defaultExpirationPolicy(ignored -> false)
						.defaultCorePoolSize(coreSize)
						.defaultMaxPoolSize(1)
						.claimTimeout(new Timeout(500, MILLISECONDS))
						.build());
		final ResourceClusterAndPoolKey<String, String> key = new ResourceClusterAndPoolKey<>("cluster", "pool");
		clusters.registerResourcePool(key);
		final Callable<PoolableObject<Integer>> claim = selectFromCluster
				? () -> clusters.claimResourceFromCluster("cluster") : () -> clusters.claimResourceFromPool(key);
		final ExecutorService executor = Executors.newSingleThreadExecutor();
		final AtomicReference<Thread> waitingThread = new AtomicReference<>();
		final AtomicReference<PoolableObject<Integer>> replacement = new AtomicReference<>();
		final PoolableObject<Integer> original = claim.call();
		try {
			assertThat(original).isNotNull();
			final Future<PoolableObject<Integer>> waiting = executor.submit(() -> {
				waitingThread.set(Thread.currentThread());
				final PoolableObject<Integer> result = claim.call();
				replacement.set(result);
				return result;
			});
			awaitBlockedClaim(waitingThread);
			original.invalidate();
			final PoolableObject<Integer> recovered = waiting.get(2, SECONDS);
			assertThat(recovered).isNotNull().isNotSameAs(original);
			assertThat(recovered.getAllocatedObject()).isNotEqualTo(original.getAllocatedObject());
			assertThat(clusters.countLiveResources()).isOne();
		} finally {
			executor.shutdownNow();
			assertThat(executor.awaitTermination(3, SECONDS)).isTrue();
			if (original != null) {
				original.invalidate();
			}
			if (replacement.get() != null) {
				replacement.get().release();
			}
			clusters.shutDown().get(3, SECONDS);
		}
	}

	private static void awaitBlockedClaim(final AtomicReference<Thread> thread) throws InterruptedException {
		final long deadline = System.nanoTime() + SECONDS.toNanos(2);
		while (System.nanoTime() < deadline) {
			final Thread worker = thread.get();
			if (worker != null && worker.getState() == Thread.State.TIMED_WAITING) {
				for (StackTraceElement frame : worker.getStackTrace()) {
					if (frame.getClassName().equals("org.bbottema.genericobjectpool.GenericObjectPool")
							&& frame.getMethodName().equals("waitForAvailableObjectOrTimeout")) {
						return;
					}
				}
			}
			Thread.sleep(1);
		}
		fail("The caller did not reach the underlying pool's condition wait");
	}
}
