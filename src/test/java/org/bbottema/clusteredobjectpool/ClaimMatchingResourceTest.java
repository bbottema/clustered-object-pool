package org.bbottema.clusteredobjectpool;

import org.bbottema.clusteredobjectpool.core.api.ResourceKey.ResourceClusterAndPoolKey;
import org.bbottema.genericobjectpool.ExpirationPolicy;
import org.bbottema.genericobjectpool.PoolableObject;
import org.bbottema.genericobjectpool.util.Timeout;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaimMatchingResourceTest extends RoundRobinResourceClustersTestBase {

	private static final UUID keyCluster1 = UUID.randomUUID();

	@Override
	public TestableDummyClustersDefaultAutoSizing createTestableClusters() {
		return new TestableDummyClustersDefaultAutoSizing(new DummyAllocatorFactory(), new ExpirationPolicy<String>() {
			@Override
			public boolean hasExpired(final PoolableObject<String> poolableObject) {
				return false;
			}
		});
	}

	@Test
	void claimMatchingResourceFromPoolClaimsIdleResourceWithoutRunningMaintenanceInsideThePredicate() throws InterruptedException {
		final ResourceClusterAndPoolKey<UUID, String> key = new ResourceClusterAndPoolKey<>(keyCluster1, "server_A");
		final AtomicLong matchedIdleAgeMs = new AtomicLong();

		clusters.registerResourcePool(key);

		assertTrue(waitUntilAllocated(4, 250));

		final PoolableObject<String> poolable = clusters.claimMatchingResourceFromPool(key, new Predicate<PoolableObject<String>>() {
			@Override
			public boolean test(final PoolableObject<String> poolableObject) {
				final long idleAgeMs = poolableObject.idleAgeMs();
				if (idleAgeMs >= 50) {
					matchedIdleAgeMs.set(idleAgeMs);
					return true;
				}
				return false;
			}
		}, new Timeout(500, MILLISECONDS));

		assertThat(poolable).isNotNull();
		assertThat(matchedIdleAgeMs.get()).isGreaterThanOrEqualTo(50);
		assertThat(requireNonNull(poolable).idleAgeMs()).isZero();

		poolable.release();
	}

	@Test
	void claimMatchingResourceFromPoolDoesNotRegisterMissingPools() throws InterruptedException {
		final ResourceClusterAndPoolKey<UUID, String> key = new ResourceClusterAndPoolKey<>(keyCluster1, "server_A");

		final PoolableObject<String> poolable = clusters.claimMatchingResourceFromPool(key, new Predicate<PoolableObject<String>>() {
			@Override
			public boolean test(final PoolableObject<String> poolableObject) {
				return true;
			}
		}, new Timeout(25, MILLISECONDS));

		assertThat(poolable).isNull();
		assertThat(clusters.isPoolRegistered(key)).isFalse();
		assertThat(clusters.countLiveResources()).isZero();
	}
}
