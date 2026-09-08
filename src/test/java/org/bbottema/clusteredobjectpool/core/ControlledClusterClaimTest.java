package org.bbottema.clusteredobjectpool.core;

import org.bbottema.clusteredobjectpool.core.api.AllocatorFactory;
import org.bbottema.clusteredobjectpool.core.api.ResourceKey.ResourceClusterAndPoolKey;
import org.bbottema.clusteredobjectpool.cyclingstrategies.RoundRobinLoadBalancing;
import org.bbottema.genericobjectpool.AllocationContext;
import org.bbottema.genericobjectpool.Allocator;
import org.bbottema.genericobjectpool.ClaimControl;
import org.bbottema.genericobjectpool.ClaimOptions;
import org.bbottema.genericobjectpool.PoolableObject;
import org.bbottema.genericobjectpool.util.Timeout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ControlledClusterClaimTest {
	private final ExecutorService workers = Executors.newCachedThreadPool();
	private final List<ResourceClusters<String, String, Integer>> owned = new ArrayList<>();
	private final Queue<PoolableObject<Integer>> leases = new ConcurrentLinkedQueue<>();
	private final AtomicInteger factories = new AtomicInteger();
	private final AtomicInteger allocations = new AtomicInteger();

	@AfterEach
	void cleanUp() throws Exception {
		final List<Future<?>> shutdowns = new ArrayList<>();
		for (final ResourceClusters<?, ?, ?> clusters : owned) {
			shutdowns.add(clusters.shutDown());
		}
		workers.shutdownNow();
		assertThat(workers.awaitTermination(5, SECONDS)).isTrue();
		for (final PoolableObject<?> lease : leases) {
			lease.invalidate();
		}
		for (final Future<?> shutdown : shutdowns) {
			result(shutdown);
		}
	}

	@ParameterizedTest
	@ValueSource(ints = {0, 1, 2})
	void preCancelledRoutesCreateNothing(final int route) {
		final ResourceClusters<String, String, Integer> clusters = clusters(defaultConfig());
		final ClaimControl control = new ClaimControl();
		control.requestCancellation();
		assertThatThrownBy(() -> claim(clusters, route, options(control))).isInstanceOf(CancellationException.class);
		assertThat(clusters.isClusterRegistered("cluster")).isFalse();
		assertThat(factories.get()).isZero();
		assertThat(allocations.get()).isZero();
	}

	@ParameterizedTest
	@ValueSource(ints = {0, 1, 2})
	void cancellationDoesNotRetireThePoolOrDisturbOtherClaims(final int route) throws Exception {
		final ResourceClusters<String, String, Integer> clusters = clusters(defaultConfig());
		final PoolableObject<Integer> held = remember(clusters.claimResourceFromPool(key("cluster", "pool")));
		final ClaimControl control = new ClaimControl();
		final AtomicReference<Thread> caller = new AtomicReference<>();
		final Future<?> cancelled = workers.submit(() -> {
			caller.set(Thread.currentThread());
			return claim(clusters, route, options(control));
		});
		awaitAvailability(caller);
		final PoolableObject<Integer> neighbour = remember(clusters.claimResourceFromPool(key("other", "pool"), ClaimOptions.withoutTimeout()));
		control.requestCancellation();
		assertThatThrownBy(() -> result(cancelled)).hasCauseInstanceOf(CancellationException.class);
		assertThat(clusters.isPoolRegistered(key("cluster", "pool"))).isTrue();
		assertThat(neighbour.getAllocatedObject()).isNotEqualTo(held.getAllocatedObject());
		held.release();
		assertThat(claim(clusters, route, ClaimOptions.withoutTimeout())).isSameAs(held);
	}

	@Test
	void matchingAnAbsentPoolNeitherRegistersNorAllocates() throws Exception {
		final ResourceClusters<String, String, Integer> clusters = clusters(defaultConfig());
		assertThat(claim(clusters, 2, ClaimOptions.withoutTimeout())).isNull();
		assertThat(clusters.isClusterRegistered("cluster")).isFalse();
		assertThat(factories.get()).isZero();
	}

	@ParameterizedTest
	@ValueSource(ints = {0, 1, 2})
	void interruptedWaitDoesNotPoisonThePool(final int route) throws Exception {
		final ResourceClusters<String, String, Integer> clusters = clusters(defaultConfig());
		final PoolableObject<Integer> held = remember(clusters.claimResourceFromPool(key("cluster", "pool")));
		final AtomicReference<Thread> caller = new AtomicReference<>();
		final Future<?> pending = workers.submit(() -> {
			caller.set(Thread.currentThread());
			return claim(clusters, route, ClaimOptions.withoutTimeout());
		});
		awaitAvailability(caller);
		caller.get().interrupt();
		assertThatThrownBy(() -> result(pending)).hasCauseInstanceOf(InterruptedException.class);
		held.release();
		assertThat(claim(clusters, route, ClaimOptions.withoutTimeout())).isSameAs(held);
	}

	@ParameterizedTest
	@ValueSource(ints = {0, 1, 2})
	void budgetExpiryDoesNotPoisonThePool(final int route) throws Exception {
		final ResourceClusters<String, String, Integer> clusters = clusters(defaultConfig());
		final PoolableObject<Integer> held = remember(clusters.claimResourceFromPool(key("cluster", "pool")));
		assertThat(claim(clusters, route, ClaimOptions.withTimeout(30, MILLISECONDS))).isNull();
		held.release();
		assertThat(claim(clusters, route, ClaimOptions.withoutTimeout())).isSameAs(held);
	}

	@Test
	void registryLockWaitingIsCancellable() throws Exception {
		final ResourceClusters<String, String, Integer> clusters = clusters(defaultConfig());
		final Field field = ResourceClusters.class.getDeclaredField("registryLock");
		field.setAccessible(true);
		final ReentrantLock lock = (ReentrantLock) field.get(clusters);
		final ClaimControl control = new ClaimControl();
		lock.lock();
		try {
			final Future<?> pending = workers.submit(() -> claim(clusters, 1, options(control)));
			await("waiting for registry", lock::hasQueuedThreads);
			control.requestCancellation();
			assertThatThrownBy(() -> result(pending)).hasCauseInstanceOf(CancellationException.class);
		} finally {
			lock.unlock();
		}
		assertThat(factories.get()).isZero();
	}

	@Test
	void concurrentFirstClaimsShareRegistrationWhenTheInitiatorCancels() throws Exception {
		final CountDownLatch creating = new CountDownLatch(1);
		final CountDownLatch finish = new CountDownLatch(1);
		final ResourceClusters<String, String, Integer> clusters = clusters(config(key -> {
			factories.incrementAndGet();
			creating.countDown();
			awaitLatch(finish);
			return allocator();
		}, 5000));
		try {
			final ClaimControl control = new ClaimControl();
			final Future<?> initiator = workers.submit(() -> claim(clusters, 1, options(control)));
			awaitLatch(creating);
			final Future<?> follower = workers.submit(() -> claim(clusters, 1, ClaimOptions.withoutTimeout()));
			final ClaimControl cancelledFollower = new ClaimControl();
			final Future<?> departing = workers.submit(() -> claim(clusters, 1, options(cancelledFollower)));
			cancelledFollower.requestCancellation();
			assertThatThrownBy(() -> result(departing)).hasCauseInstanceOf(CancellationException.class);
			control.requestCancellation();
			assertThat(initiator.isDone()).isFalse();
			finish.countDown();
			assertThatThrownBy(() -> result(initiator)).hasCauseInstanceOf(CancellationException.class);
			assertThat(result(follower)).isNotNull();
			assertThat(factories.get()).isEqualTo(1);
			assertThat(allocations.get()).isEqualTo(1);
		} finally {
			finish.countDown();
		}
	}

	@Test
	void registrationConsumesTheCallerBudgetWithoutStartingResourceAllocationAfterExpiry() throws Exception {
		final CountDownLatch creating = new CountDownLatch(1);
		final CountDownLatch finish = new CountDownLatch(1);
		final ResourceClusters<String, String, Integer> clusters = clusters(config(key -> {
			creating.countDown();
			awaitLatch(finish);
			return allocator();
		}, 5000));
		try {
			final Future<?> pending = workers.submit(() -> claim(clusters, 1, ClaimOptions.withTimeout(80, MILLISECONDS)));
			awaitLatch(creating);
			Thread.sleep(120); // Let the real monotonic budget expire inside this non-cooperative factory.
			finish.countDown();
			assertThat(result(pending)).isNull();
			assertThat(allocations.get()).isZero();
			assertThat(claim(clusters, 1, ClaimOptions.withoutTimeout())).isNotNull();
		} finally {
			finish.countDown();
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void effectiveBudgetUsesTheEarlierCallerOrClusterLimit(final boolean shorterCluster) throws Exception {
		final AtomicReference<AllocationContext> observed = new AtomicReference<>();
		final ResourceClusters<String, String, Integer> clusters = clusters(config(key -> new Allocator<Integer>() {
			@Override public Integer allocate() { return 1; }
			@Override public Integer allocate(final AllocationContext context) {
				observed.set(context);
				return 1;
			}
		}, shorterCluster ? 3000 : 10000));
		remember(clusters.claimResourceFromPool(key("cluster", "pool"), ClaimOptions.withTimeout(shorterCluster ? 10 : 3, SECONDS)));
		assertThat(observed.get().getRemainingTime(MILLISECONDS)).isBetween(2000L, 3000L);
	}

	@Test
	void slowSelectionDoesNotHoldRegistryAndCannotRestartTheBudget() throws Exception {
		final CountDownLatch selecting = new CountDownLatch(1);
		final CountDownLatch finish = new CountDownLatch(1);
		final ClusterConfig<String, String, Integer> config = ClusterConfig.<String, String, Integer>builder()
				.allocatorFactory(key -> allocator()).defaultMaxPoolSize(1).defaultExpirationPolicy(value -> false)
				.loadBalancingStrategy(new RoundRobinLoadBalancing<ResourcePool<String, Integer>>() {
					@Override public ResourcePool<String, Integer> cycle(final Queue<ResourcePool<String, Integer>> pools) {
						selecting.countDown();
						awaitLatch(finish);
						return super.cycle(pools);
					}
				}).build();
		final ResourceClusters<String, String, Integer> clusters = clusters(config);
		clusters.registerResourcePool(key("cluster", "pool"));
		clusters.registerResourcePool(key("other", "pool"));
		try {
			final Future<?> pending = workers.submit(() -> claim(clusters, 0, ClaimOptions.withTimeout(80, MILLISECONDS)));
			awaitLatch(selecting);
			assertThat(remember(clusters.claimResourceFromPool(key("other", "pool"), ClaimOptions.withoutTimeout()))).isNotNull();
			assertThat(clusters.countLiveResources()).isEqualTo(1);
			final ClaimControl control = new ClaimControl();
			final Future<?> blockedSelection = workers.submit(() -> claim(clusters, 0, options(control)));
			control.requestCancellation();
			assertThatThrownBy(() -> result(blockedSelection)).hasCauseInstanceOf(CancellationException.class);
			Thread.sleep(120);
			finish.countDown();
			assertThat(result(pending)).isNull();
		} finally {
			finish.countDown();
		}
	}

	@Test
	void shutdownIncludesLateRegistrationAndDoesNotRemoveTheNextGeneration() throws Exception {
		final CountDownLatch creating = new CountDownLatch(1);
		final CountDownLatch finish = new CountDownLatch(1);
		final ResourceClusters<String, String, Integer> clusters = clusters(config(key -> {
			if (factories.incrementAndGet() == 1) {
				creating.countDown();
				awaitLatch(finish);
			}
			return allocator();
		}, 5000));
		try {
			final Future<?> first = workers.submit(() -> claim(clusters, 1, ClaimOptions.withoutTimeout()));
			awaitLatch(creating);
			final Future<Void> retired = clusters.shutdownPool("pool");
			assertThat(retired.isDone()).isFalse();
			final PoolableObject<Integer> next = remember(clusters.claimResourceFromPool(key("cluster", "pool")));
			finish.countDown();
			assertThatThrownBy(() -> result(first)).hasCauseInstanceOf(IllegalStateException.class);
			result(retired);
			assertThat(clusters.isPoolRegistered(key("cluster", "pool"))).isTrue();
			assertThat(next.getAllocatedObject()).isEqualTo(1);
			assertThat(clusters.countLiveResources()).isEqualTo(1);
		} finally {
			finish.countDown();
		}
	}

	@Test
	void aSharedControlCancelsItsPendingClaimsButNotItsEarlierBorrower() throws Exception {
		final ResourceClusters<String, String, Integer> clusters = clusters(defaultConfig());
		final ClaimControl control = new ClaimControl();
		final PoolableObject<Integer> held = claim(clusters, 1, options(control));
		final List<Future<?>> pending = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			pending.add(workers.submit(() -> claim(clusters, 1, options(control))));
		}
		control.requestCancellation();
		for (final Future<?> claim : pending) {
			assertThatThrownBy(() -> result(claim)).hasCauseInstanceOf(CancellationException.class);
		}
		assertThat(held.getAllocatedObject()).isEqualTo(1);
		held.release();
		assertThat(claim(clusters, 1, ClaimOptions.withoutTimeout())).isSameAs(held);
	}

	private ClusterConfig<String, String, Integer> defaultConfig() {
		return config(key -> { factories.incrementAndGet(); return allocator(); }, 5000);
	}

	private ClusterConfig<String, String, Integer> config(final AllocatorFactory<String, String, Integer> factory, final long timeoutMs) {
		return ClusterConfig.<String, String, Integer>builder().allocatorFactory(factory).defaultMaxPoolSize(1)
				.defaultExpirationPolicy(value -> false).claimTimeout(new Timeout(timeoutMs, MILLISECONDS)).build();
	}

	private Allocator<Integer> allocator() {
		return new Allocator<Integer>() {
			@Override public Integer allocate() { return allocations.incrementAndGet(); }
		};
	}

	private ResourceClusters<String, String, Integer> clusters(final ClusterConfig<String, String, Integer> config) {
		final ResourceClusters<String, String, Integer> clusters = new ResourceClusters<>(config);
		owned.add(clusters);
		return clusters;
	}

	private PoolableObject<Integer> claim(final ResourceClusters<String, String, Integer> clusters, final int route, final ClaimOptions options)
			throws InterruptedException {
		switch (route) {
			case 0: return remember(clusters.claimResourceFromCluster("cluster", options));
			case 1: return remember(clusters.claimResourceFromPool(key("cluster", "pool"), options));
			case 2: return remember(clusters.claimMatchingResourceFromPool(key("cluster", "pool"), value -> true, options));
			default: throw new AssertionError("Unknown test route");
		}
	}

	private PoolableObject<Integer> remember(final PoolableObject<Integer> lease) {
		if (lease != null) {
			leases.add(lease);
		}
		return lease;
	}

	private static ResourceClusterAndPoolKey<String, String> key(final String cluster, final String pool) {
		return new ResourceClusterAndPoolKey<>(cluster, pool);
	}

	private static ClaimOptions options(final ClaimControl control) {
		return ClaimOptions.withTimeout(5, SECONDS).withClaimControl(control);
	}

	private static <T> T result(final Future<T> result) throws Exception {
		return result.get(5, SECONDS);
	}

	private static void awaitAvailability(final AtomicReference<Thread> thread) throws InterruptedException {
		await("waiting for resource availability", () -> thread.get() != null && Arrays.stream(thread.get().getStackTrace())
				.anyMatch(frame -> frame.getMethodName().equals("awaitAvailability")));
	}

	private static void await(final String description, final BooleanSupplier condition) throws InterruptedException {
		final long started = System.nanoTime();
		while (!condition.getAsBoolean() && System.nanoTime() - started < SECONDS.toNanos(5)) {
			Thread.sleep(2);
		}
		assertThat(condition.getAsBoolean()).as(description).isTrue();
	}

	private static void awaitLatch(final CountDownLatch latch) {
		try {
			assertThat(latch.await(5, SECONDS)).as("test latch released").isTrue();
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new AssertionError(interrupted);
		}
	}
}
