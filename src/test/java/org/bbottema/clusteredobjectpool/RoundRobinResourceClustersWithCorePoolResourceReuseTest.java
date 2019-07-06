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
package org.bbottema.clusteredobjectpool;

import org.bbottema.clusteredobjectpool.core.api.ResourceKey.ResourceClusterAndPoolKey;
import org.bbottema.genericobjectpool.ExpirationPolicy;
import org.bbottema.genericobjectpool.PoolableObject;
import org.bbottema.genericobjectpool.expirypolicies.TimeoutSinceLastAllocationExpirationPolicy;
import org.junit.Test;

import java.util.UUID;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings("SameParameterValue")
public class RoundRobinResourceClustersWithCorePoolResourceReuseTest extends RoundRobinResourceClustersTestBase {
	
	private static final UUID keyCluster1 = UUID.randomUUID();
	private static final UUID keyCluster2 = UUID.randomUUID();
	private static final UUID keyCluster3 = UUID.randomUUID();
	private static final int QUICK_EXPIRY_TIMEOUT_MS = 800;
	private static final int SLEEP_TIMEOUT_AFTER_CLAIMING_MS = 1000;
	
	public TestableDummyClustersDefaultAutoSizing createTestableClusters() {
		ExpirationPolicy<String> expireAlmostImmediately = new TimeoutSinceLastAllocationExpirationPolicy<>(QUICK_EXPIRY_TIMEOUT_MS, MILLISECONDS);
		return new TestableDummyClustersDefaultAutoSizing(new DummyAllocatorFactory(), expireAlmostImmediately);
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
		assertThat(claimAndReleaseResource(keyCluster1)).isEqualTo("connection_A4"); // Still A, because no cycling happened yet,
		// also should be A4, because it has been waiting for longer than 3
		assertThat(claimAndNoReleaseResource(keyCluster1)).isEqualTo("connection_B1");
		MILLISECONDS.sleep(SLEEP_TIMEOUT_AFTER_CLAIMING_MS);
		assertThat(claimAndNoReleaseResource(keyCluster1)).isEqualTo("connection_A5");
		assertThat(claimAndNoReleaseResource(keyCluster1)).isEqualTo("connection_B5");
		assertThat(claimAndReleaseResource(keyCluster1)).isEqualTo("connection_A6");
		connectionA1.release();
		assertThat(claimAndNoReleaseResource(keyCluster1)).isEqualTo("connection_B6");
		assertThat(claimAndNoReleaseResource(keyCluster1)).isEqualTo("connection_A6");
		assertThat(claimAndNoReleaseResource(keyCluster1)).isEqualTo("connection_B7");
		assertThat(claimAndNoReleaseResource(keyCluster1)).isEqualTo("connection_A1");
		// cluster 2
		assertThat(claimAndNoReleaseResource(keyCluster2)).isEqualTo("connection_C5");
		assertThat(claimAndNoReleaseResource(keyCluster2)).isEqualTo("connection_D5");
		assertThat(claimAndNoReleaseResource(keyCluster2)).isEqualTo("connection_C6");
		assertThat(claimAndNoReleaseResource(keyCluster2)).isEqualTo("connection_D6");
		assertThat(claimAndNoReleaseResource(keyCluster2)).isEqualTo("connection_C7");
		assertThat(claimAndNoReleaseResource(keyCluster2)).isEqualTo("connection_D7");
		assertThat(claimAndNoReleaseResource(keyCluster2)).isEqualTo("connection_C8");
		
		// all allocated resources
		assertThat(clusters.countLiveResources()).isEqualTo(20);
		
		clusters.shutDown();
		MILLISECONDS.sleep(50);
		
		// allocated resources after clearing released resources
		assertThat(clusters.countLiveResources()).isEqualTo(15);
		
		for (PoolableObject<String> claimedResource : claimedResources) {
			claimedResource.release();
		}
		
		MILLISECONDS.sleep(50);
		
		// allocated resources after clearing all resources
		assertThat(clusters.countLiveResources()).isEqualTo(0);
	}
}