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
import org.bbottema.genericobjectpool.expirypolicies.TimeoutSinceCreationExpirationPolicy;
import org.junit.Test;

import java.util.UUID;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings("SameParameterValue")
public class RoundRobinResourceClustersWithCustomOnDemandSizingTest extends RoundRobinResourceClustersTestBase {
	
	private static final UUID keyCluster1 = UUID.randomUUID();
	private static final UUID keyCluster2 = UUID.randomUUID();
	private static final UUID keyCluster3 = UUID.randomUUID();
	
	public TestableDummyClustersOnDemandSizing createTestableClusters() {
		ExpirationPolicy<String> expireAfter10Seconds = new TimeoutSinceCreationExpirationPolicy<>(10, SECONDS);
		return new TestableDummyClustersOnDemandSizing(new DummyAllocatorFactory(), expireAfter10Seconds);
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
		assertThat(requireNonNull(connectionA1).getAllocatedObject()).isEqualTo("connection_A1");
		assertThat(claimAndNoReleaseForKey(keyCluster1, "server_A")).isEqualTo("connection_A2");
		assertThat(claimAndReleaseForKey(keyCluster1, "server_A")).isEqualTo("connection_A3");
		
		// now claim on clusters
		// cluster 1
		assertThat(claimAndReleaseResource(keyCluster1)).isEqualTo("connection_A3");
		assertThat(claimAndNoReleaseResource(keyCluster1)).isEqualTo("connection_B1");
		assertThat(claimAndNoReleaseResource(keyCluster1)).isEqualTo("connection_A3");
		assertThat(claimAndNoReleaseResource(keyCluster1)).isEqualTo("connection_B2");
		assertThat(claimAndReleaseResource(keyCluster1)).isEqualTo("connection_A4");
		connectionA1.release();
		assertThat(claimAndNoReleaseResource(keyCluster1)).isEqualTo("connection_B3");
		assertThat(claimAndNoReleaseResource(keyCluster1)).isEqualTo("connection_A4");
		assertThat(claimAndNoReleaseResource(keyCluster1)).isEqualTo("connection_B4");
		assertThat(claimAndNoReleaseResource(keyCluster1)).isEqualTo("connection_A1");
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
		
		clusters.shutDown();
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