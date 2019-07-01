package org.bbottema.clusteredobjectpool;

import org.bbottema.clusteredobjectpool.core.api.ResourceKey.ResourceClusterAndPoolKey;
import org.bbottema.genericobjectpool.ExpirationPolicy;
import org.bbottema.genericobjectpool.PoolableObject;
import org.bbottema.genericobjectpool.expirypolicies.TimeoutSinceCreationExpirationPolicy;
import org.junit.Test;

import java.util.UUID;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings("SameParameterValue")
public class RoundRobinResourceClustersWithCorePoolTest extends RoundRobinResourceClustersTestBase {
	
	private static final UUID keyCluster1 = UUID.randomUUID();
	private static final UUID keyCluster2 = UUID.randomUUID();
	private static final UUID keyCluster3 = UUID.randomUUID();
	
	public TestableDummyClustersDefaultAutoSizing createTestableClusters() {
		final ExpirationPolicy<String> defaultExpiration = new TimeoutSinceCreationExpirationPolicy<>(10, SECONDS);
		return new TestableDummyClustersDefaultAutoSizing(new DummyAllocatorFactory(), defaultExpiration);
	}
	
	@Test
	public void testRoundRobinDummyClusters() throws InterruptedException {
		clusters.registerResourcePool(new ResourceClusterAndPoolKey<>(keyCluster1, "server_A"));
		clusters.registerResourcePool(new ResourceClusterAndPoolKey<>(keyCluster1, "server_B"));
		clusters.registerResourcePool(new ResourceClusterAndPoolKey<>(keyCluster2, "server_C"));
		clusters.registerResourcePool(new ResourceClusterAndPoolKey<>(keyCluster2, "server_D"));
		clusters.registerResourcePool(new ResourceClusterAndPoolKey<>(keyCluster3, "server_E"));
		
		// first claim on a few specific servers
		PoolableObject<String> connectionA1 = clusters.claimResourceFromPool(new ResourceClusterAndPoolKey<>(keyCluster1, "server_A"));
		PoolableObject<String> connectionA2 = clusters.claimResourceFromPool(new ResourceClusterAndPoolKey<>(keyCluster1, "server_A"));
		
		assertThat(requireNonNull(connectionA1).getAllocatedObject()).isEqualTo("connection_A1");
		assertThat(requireNonNull(connectionA2).getAllocatedObject()).isEqualTo("connection_A2");
		assertThat(claimAndReleaseForKey(keyCluster1, "server_A")).isEqualTo("connection_A3");
		connectionA2.invalidate(); // won't show up again
		
		// now claim on clusters
		// cluster 1
		assertThat(claimAndReleaseResource(keyCluster1)).isEqualTo("connection_A4");
		assertThat(claimAndNoReleaseResource(keyCluster1)).isEqualTo("connection_B1");
		assertThat(claimAndNoReleaseResource(keyCluster1)).isEqualTo("connection_A3");
		assertThat(claimAndNoReleaseResource(keyCluster1)).isEqualTo("connection_B2");
		assertThat(claimAndReleaseResource(keyCluster1)).isEqualTo("connection_A4");
		connectionA1.release();
		assertThat(claimAndNoReleaseResource(keyCluster1)).isEqualTo("connection_B3");
		assertThat(claimAndNoReleaseResource(keyCluster1)).isEqualTo("connection_A4");
		assertThat(claimAndNoReleaseResource(keyCluster1)).isEqualTo("connection_B4");
		assertThat(claimAndNoReleaseResource(keyCluster1)).isEqualTo("connection_A1");
		PoolableObject<String> connectionA5 = clusters.claimResourceFromPool(new ResourceClusterAndPoolKey<>(keyCluster1, "server_A"));
		assertThat(requireNonNull(connectionA5).getAllocatedObject()).isEqualTo("connection_A5"); // possible because A2 was marked faulty and free up an extra spot
		claimedResources.add(connectionA5);
		// cluster 2
		assertThat(claimAndNoReleaseResource(keyCluster2)).isEqualTo("connection_C1");
		assertThat(claimAndNoReleaseResource(keyCluster2)).isEqualTo("connection_D1");
		assertThat(claimAndNoReleaseResource(keyCluster2)).isEqualTo("connection_C2");
		assertThat(claimAndNoReleaseResource(keyCluster2)).isEqualTo("connection_D2");
		assertThat(claimAndNoReleaseResource(keyCluster2)).isEqualTo("connection_C3");
		assertThat(claimAndNoReleaseResource(keyCluster2)).isEqualTo("connection_D3");
		assertThat(claimAndNoReleaseResource(keyCluster2)).isEqualTo("connection_C4");
		
		// all allocated resources
		MILLISECONDS.sleep(50);
		assertThat(clusters.countLiveResources()).isEqualTo(20);
		
		clusters.clearPools();
		MILLISECONDS.sleep(50);
		
		// allocated resources after clearing released resources
		assertThat(clusters.countLiveResources()).isEqualTo(15);
		
		for (PoolableObject claimedResource : claimedResources) {
			claimedResource.release();
		}
		
		MILLISECONDS.sleep(50);
		
		// allocated resources after clearing all resources
		assertThat(clusters.countLiveResources()).isEqualTo(0);
	}
}