package org.bbottema.clusteredobjectpool.core;

import org.bbottema.clusteredobjectpool.core.api.ResourceKey.ResourceClusterAndPoolKey;
import org.bbottema.clusteredobjectpool.cyclingstrategies.RoundRobinLoadBalancing;
import org.bbottema.genericobjectpool.Allocator;
import org.bbottema.genericobjectpool.ClaimControl;
import org.bbottema.genericobjectpool.ClaimOptions;
import org.bbottema.genericobjectpool.PoolableObject;
import org.bbottema.genericobjectpool.util.Timeout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResourcePoolSelectionTest {

	private final ExecutorService workers = Executors.newCachedThreadPool();
	private final List<ResourceClusters<String, String, String>> owned = new ArrayList<>();
	private final AtomicInteger allocations = new AtomicInteger();
	private final AtomicInteger selections = new AtomicInteger();

	@AfterEach
	void close() throws Exception {
		workers.shutdownNow();
		assertThat(workers.awaitTermination(5, SECONDS)).isTrue();
		for (final ResourceClusters<?, ?, ?> clusters : owned) {
			clusters.shutDown().get(5, SECONDS);
		}
	}

	@Test
	void selectionDoesNotAllocateAndClaimsDoNotCycleAgain() throws Exception {
		final ResourceClusters<String, String, String> clusters = clusters(strategy());
		register(clusters, "one", "two");
		final ResourcePoolSelection<String, String> one = clusters.selectPoolFromCluster("cluster");
		final ResourcePoolSelection<String, String> two = clusters.selectPoolFromCluster("cluster", ClaimOptions.withoutTimeout());
		assertThat(one.getPoolKey()).isEqualTo("one");
		assertThat(two.getPoolKey()).isEqualTo("two");
		assertThat(allocations.get()).isZero();
		assertThat(clusters.countLiveResources()).isZero();
		assertThat(selections.get()).isEqualTo(2);
		assertClaim(two, "two");
		assertClaim(one, "one");
		assertThat(selections.get()).isEqualTo(2);
		assertThat(clusters.selectPoolFromCluster("cluster").getPoolKey()).isEqualTo("one");
	}

	@Test
	void missingSelectionNeverRegistersAnything() {
		final ResourceClusters<String, String, String> clusters = clusters(strategy());
		assertThatThrownBy(() -> clusters.selectPoolFromCluster("missing")).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> clusters.selectPool(key("missing"))).isInstanceOf(IllegalStateException.class);
		assertThat(clusters.isClusterRegistered("missing")).isFalse();
		assertThat(clusters.isClusterRegistered("cluster")).isFalse();
		assertThat(allocations.get()).isZero();
	}

	@Test
	void addressedSelectionDoesNotAdvanceTheClusterStrategy() throws Exception {
		final ResourceClusters<String, String, String> clusters = clusters(strategy());
		register(clusters, "one", "two");
		assertClaim(clusters.selectPool(key("two")), "two");
		assertThat(selections.get()).isZero();
		assertThat(clusters.selectPoolFromCluster("cluster").getPoolKey()).isEqualTo("one");
	}

	@Test
	void retirementDoesNotRedirectTheSelectionToANewRegistration() throws Exception {
		final ResourceClusters<String, String, String> clusters = clusters(strategy());
		register(clusters, "one");
		final ResourcePoolSelection<String, String> previous = clusters.selectPoolFromCluster("cluster");
		clusters.shutdownPool("one").get(5, SECONDS);
		register(clusters, "one");
		assertThatThrownBy(previous::claim).isInstanceOf(IllegalStateException.class).hasMessageContaining("retired");
		assertThatThrownBy(() -> previous.claim(ClaimOptions.withoutTimeout())).isInstanceOf(IllegalStateException.class);
		assertThat(allocations.get()).isZero();
		assertClaim(clusters.selectPoolFromCluster("cluster"), "one");
	}

	@Test
	void cancellationBeforeSelectionOrClaimAllocatesNothing() throws Exception {
		final ResourceClusters<String, String, String> clusters = clusters(strategy());
		register(clusters, "one");
		final ResourcePoolSelection<String, String> selected = clusters.selectPoolFromCluster("cluster");
		final ClaimControl control = new ClaimControl();
		control.requestCancellation();
		final ClaimOptions options = ClaimOptions.withoutTimeout().withClaimControl(control);
		assertThatThrownBy(() -> clusters.selectPoolFromCluster("cluster", options)).isInstanceOf(CancellationException.class);
		assertThatThrownBy(() -> clusters.selectPool(key("one"), options)).isInstanceOf(CancellationException.class);
		assertThatThrownBy(() -> selected.claim(options)).isInstanceOf(CancellationException.class);
		assertThat(selections.get()).isEqualTo(1);
		assertThat(allocations.get()).isZero();
		assertClaim(selected, "one");
	}

	@Test
	void cancelledSlowSelectionReleasesNoResourceAndDoesNotHoldRegistry() throws Exception {
		final CountDownLatch selecting = new CountDownLatch(1);
		final CountDownLatch resume = new CountDownLatch(1);
		final ResourceClusters<String, String, String> clusters = clusters(new RoundRobinLoadBalancing<ResourcePool<String, String>>() {
			@Override
			public ResourcePool<String, String> cycle(final Queue<ResourcePool<String, String>> pools) {
				selecting.countDown();
				await(resume);
				return super.cycle(pools);
			}
		});
		register(clusters, "one");
		final ClaimControl control = new ClaimControl();
		try {
			final Future<?> pending = workers.submit(() -> clusters.selectPoolFromCluster("cluster",
					ClaimOptions.withoutTimeout().withClaimControl(control)));
			await(selecting);
			register(clusters, "two");
			clusters.shutdownPool("one").get(5, SECONDS);
			control.requestCancellation();
			resume.countDown();
			assertThatThrownBy(() -> pending.get(5, SECONDS)).hasCauseInstanceOf(CancellationException.class);
			assertThat(allocations.get()).isZero();
			assertClaim(clusters.selectPool(key("two")), "two");
		} finally {
			resume.countDown();
		}
	}

	@Test
	void separateSelectionsCanClaimConcurrentlyAndTimeoutDoesNotPoisonThem() throws Exception {
		final ResourceClusters<String, String, String> clusters = clusters(strategy());
		register(clusters, "one");
		final ResourcePoolSelection<String, String> selected = clusters.selectPoolFromCluster("cluster");
		final PoolableObject<String> held = selected.claim();
		try {
			assertThat(workers.submit(() -> selected.claim(ClaimOptions.withTimeout(20, MILLISECONDS))).get(5, SECONDS)).isNull();
		} finally {
			held.release();
		}
		assertClaim(selected, "one");
		assertThat(allocations.get()).isEqualTo(1);
	}

	@Test
	void cancellationAndTimeoutCanLeaveTheSelectionGateWhileAnotherCallbackIsStillRunning() throws Exception {
		final CountDownLatch selecting = new CountDownLatch(1);
		final CountDownLatch resume = new CountDownLatch(1);
		final ResourceClusters<String, String, String> clusters = clusters(new RoundRobinLoadBalancing<ResourcePool<String, String>>() {
			@Override
			public ResourcePool<String, String> cycle(final Queue<ResourcePool<String, String>> pools) {
				selecting.countDown();
				await(resume);
				return super.cycle(pools);
			}
		});
		register(clusters, "one");
		try {
			final Future<?> first = workers.submit(() -> clusters.selectPoolFromCluster("cluster"));
			await(selecting);
			final Future<?> timed = workers.submit(() -> clusters.selectPoolFromCluster("cluster", ClaimOptions.withTimeout(20, MILLISECONDS)));
			assertThat(timed.get(5, SECONDS)).isNull();
			final ClaimControl control = new ClaimControl();
			final Future<?> cancelled = workers.submit(() -> clusters.selectPoolFromCluster("cluster",
					ClaimOptions.withoutTimeout().withClaimControl(control)));
			control.requestCancellation();
			assertThatThrownBy(() -> cancelled.get(5, SECONDS)).hasCauseInstanceOf(CancellationException.class);
			assertThat(first.isDone()).isFalse();
			assertThat(allocations.get()).isZero();
			resume.countDown();
			assertThat(first.get(5, SECONDS)).isNotNull();
		} finally {
			resume.countDown();
		}
	}

	@Test
	void selectionDoesNotJoinInProgressPoolInitialization() throws Exception {
		final CountDownLatch creating = new CountDownLatch(1);
		final CountDownLatch resume = new CountDownLatch(1);
		final ResourceClusters<String, String, String> clusters = new ResourceClusters<>(ClusterConfig.<String, String, String>builder()
				.allocatorFactory(key -> {
					creating.countDown();
					await(resume);
					return new Allocator<String>() {
						@Override public String allocate() { allocations.incrementAndGet(); return key.getPoolKey(); }
					};
				}).defaultMaxPoolSize(1).defaultExpirationPolicy(value -> false).build());
		owned.add(clusters);
		try {
			final Future<?> registration = workers.submit(() -> register(clusters, "one"));
			await(creating);
			final ResourcePoolSelection<String, String> selected = workers.submit(() -> clusters.selectPoolFromCluster("cluster")).get(5, SECONDS);
			assertThat(selected.getPoolKey()).isEqualTo("one");
			assertThat(registration.isDone()).isFalse();
			assertThat(allocations.get()).isZero();
			resume.countDown();
			registration.get(5, SECONDS);
			assertClaim(selected, "one");
		} finally {
			resume.countDown();
		}
	}

	@Test
	void concurrentSelectionsBalanceWithoutAllocating() throws Exception {
		final ResourceClusters<String, String, String> clusters = clusters(strategy());
		register(clusters, "one", "two");
		final List<Future<String>> pending = new ArrayList<>();
		for (int index = 0; index < 100; index++) {
			pending.add(workers.submit(() -> clusters.selectPoolFromCluster("cluster").getPoolKey()));
		}
		final List<String> keys = new ArrayList<>();
		for (final Future<String> selection : pending) {
			keys.add(selection.get(5, SECONDS));
		}
		assertThat(keys.stream().filter("one"::equals).count()).isEqualTo(50);
		assertThat(keys.stream().filter("two"::equals).count()).isEqualTo(50);
		assertThat(allocations.get()).isZero();
	}

	private RoundRobinLoadBalancing<ResourcePool<String, String>> strategy() {
		return new RoundRobinLoadBalancing<ResourcePool<String, String>>() {
			@Override
			public ResourcePool<String, String> cycle(final Queue<ResourcePool<String, String>> pools) {
				selections.incrementAndGet();
				return super.cycle(pools);
			}
		};
	}

	private ResourceClusters<String, String, String> clusters(final RoundRobinLoadBalancing<ResourcePool<String, String>> strategy) {
		final ResourceClusters<String, String, String> clusters = new ResourceClusters<>(ClusterConfig.<String, String, String>builder()
				.allocatorFactory(key -> new Allocator<String>() {
					@Override
					public String allocate() {
						allocations.incrementAndGet();
						return key.getPoolKey();
					}
				}).defaultMaxPoolSize(1).defaultExpirationPolicy(value -> false).loadBalancingStrategy(strategy)
				.claimTimeout(new Timeout(5, SECONDS)).build());
		owned.add(clusters);
		return clusters;
	}

	private static void register(final ResourceClusters<String, String, String> clusters, final String... names) {
		for (final String name : names) {
			clusters.registerResourcePool(key(name));
		}
	}

	private static ResourceClusterAndPoolKey<String, String> key(final String pool) {
		return new ResourceClusterAndPoolKey<>("cluster", pool);
	}

	private static void assertClaim(final ResourcePoolSelection<String, String> selected, final String expected) throws Exception {
		final PoolableObject<String> claimed = selected.claim(ClaimOptions.withoutTimeout());
		try {
			assertThat(claimed.getAllocatedObject()).isEqualTo(expected);
		} finally {
			claimed.release();
		}
	}

	private static void await(final CountDownLatch latch) {
		try {
			assertThat(latch.await(5, SECONDS)).isTrue();
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new AssertionError(interrupted);
		}
	}
}
