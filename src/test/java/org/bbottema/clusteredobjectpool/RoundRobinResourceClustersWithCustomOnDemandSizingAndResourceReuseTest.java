package org.bbottema.clusteredobjectpool;

import org.bbottema.clusteredobjectpool.core.api.ResourceKey.ResourceClusterAndPoolKey;
import org.bbottema.genericobjectpool.ExpirationPolicy;
import org.bbottema.genericobjectpool.PoolableObject;
import org.bbottema.genericobjectpool.expirypolicies.TimeoutSinceCreationExpirationPolicy;
import org.junit.Test;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings("SameParameterValue")
public class RoundRobinResourceClustersWithCustomOnDemandSizingAndResourceReuseTest extends RoundRobinResourceClustersTestBase {
	
	private static final UUID keyCluster1 = UUID.randomUUID();
	private static final UUID keyCluster2 = UUID.randomUUID();
	private static final UUID keyCluster3 = UUID.randomUUID();
	private static final int QUICK_EXPIRY_TIMEOUT_MS = 100;
	private static final int SLEEP_TIMEOUT_AFTER_CLAIMING_MS = 200;
	
	@Override
	public TestableDummyClustersOnDemandSizing createTestableClusters() {
		ExpirationPolicy<String> expireAlmostImmediately = new TimeoutSinceCreationExpirationPolicy<>(QUICK_EXPIRY_TIMEOUT_MS, MILLISECONDS);
		return new TestableDummyClustersOnDemandSizing(new DummyAllocatorFactory(), expireAlmostImmediately);
	}
	
	@Test
	// because of the short life of the resources, this nubers might seem a little strange (high) at first
	public void testRoundRobinDummyClusters() throws InterruptedException {
		clusters.registerResourcePool(new ResourceClusterAndPoolKey<>(keyCluster1, "server_A"));
		clusters.registerResourcePool(new ResourceClusterAndPoolKey<>(keyCluster1, "server_B"));
		clusters.registerResourcePool(new ResourceClusterAndPoolKey<>(keyCluster2, "server_C"));
		clusters.registerResourcePool(new ResourceClusterAndPoolKey<>(keyCluster2, "server_D"));
		clusters.registerResourcePool(new ResourceClusterAndPoolKey<>(keyCluster3, "server_E"));
		
		// first claim on a specific servers
		PoolableObject<String> connectionA1 = clusters.claimResourceFromPool(new ResourceClusterAndPoolKey<>(keyCluster1, "server_A"));
		assertThat(requireNonNull(connectionA1).getAllocatedObject()).isEqualTo("connection_A1");
		assertThat(claimAndNoReleaseForKey(keyCluster1, "server_A")).isEqualTo("connection_A2");
		assertThat(claimAndReleaseForKey(keyCluster1, "server_A")).isEqualTo("connection_A3");
		
		// now claim on clusters
		// cluster 1
		assertThat(claimAndReleaseResource(keyCluster1)).isEqualTo("connection_A3"); // Still A, because no cycling happened yet
		assertThat(claimAndNoReleaseResource(keyCluster1)).isEqualTo("connection_B1");
		assertThat(claimAndNoReleaseResource(keyCluster1)).isEqualTo("connection_A3"); // reuse
		MILLISECONDS.sleep(SLEEP_TIMEOUT_AFTER_CLAIMING_MS);
		assertThat(claimAndNoReleaseResource(keyCluster1)).isEqualTo("connection_B2");
		assertThat(claimAndNoReleaseResource(keyCluster1)).isEqualTo("connection_A4"); // A3 expired, new transport needed
		assertThat(clusters.countLiveResources()).isEqualTo(6);
		connectionA1.release();
		assertThat(claimAndNoReleaseResource(keyCluster1)).isEqualTo("connection_B3");
		assertThat(claimAndReleaseResource(keyCluster1)).isEqualTo("connection_A1");
		assertThat(claimAndNoReleaseResource(keyCluster1)).isEqualTo("connection_B4");
		TimeUnit.MILLISECONDS.sleep(10);
		assertThat(claimAndNoReleaseResource(keyCluster1)).isEqualTo("connection_A5");
		// cluster 2
		assertThat(claimAndNoReleaseResource(keyCluster2)).isEqualTo("connection_C1");
		assertThat(claimAndNoReleaseResource(keyCluster2)).isEqualTo("connection_D1");
		assertThat(claimAndNoReleaseResource(keyCluster2)).isEqualTo("connection_C2");
		assertThat(claimAndNoReleaseResource(keyCluster2)).isEqualTo("connection_D2");
		assertThat(claimAndNoReleaseResource(keyCluster2)).isEqualTo("connection_C3");
		assertThat(claimAndNoReleaseResource(keyCluster2)).isEqualTo("connection_D3");
		assertThat(claimAndNoReleaseResource(keyCluster2)).isEqualTo("connection_C4");
		
		// all allocated resources
		assertThat(clusters.countLiveResources()).isEqualTo(15);
		MILLISECONDS.sleep(SLEEP_TIMEOUT_AFTER_CLAIMING_MS);
		assertThat(clusters.countLiveResources()).isEqualTo(15); // still the same, all remaining resources claimed
		
		for (PoolableObject claimedResource : claimedResources) {
			claimedResource.release();
		}
		MILLISECONDS.sleep(SLEEP_TIMEOUT_AFTER_CLAIMING_MS);
		assertThat(clusters.countLiveResources()).isEqualTo(0);
		
		clusters.clearPools();
		MILLISECONDS.sleep(50);
		// allocated resources after clearing all resources
		assertThat(clusters.countLiveResources()).isEqualTo(0);
	}
	
	@Test
	// I don't really know when the disregardPile is triggered, but this situation seems to do it
	public void testDeallocateDisregardedObjects() throws InterruptedException {
		clusters.registerResourcePool(new ResourceClusterAndPoolKey<>(keyCluster1, "server_A"));
		
		claimAndReleaseResource(keyCluster1);
		claimAndNoReleaseResource(keyCluster1);
		claimAndNoReleaseResource(keyCluster1);
		
		for (PoolableObject claimedResource : claimedResources) {
			claimedResource.release();
		}
		MILLISECONDS.sleep(SLEEP_TIMEOUT_AFTER_CLAIMING_MS * 3);
		assertThat(clusters.countLiveResources()).isEqualTo(0);
	}
}