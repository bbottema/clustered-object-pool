package org.bbottema.clusteredobjectpool;

import org.bbottema.clusteredobjectpool.core.ClusterConfig;
import org.bbottema.clusteredobjectpool.core.ResourceClusters;
import org.bbottema.clusteredobjectpool.core.api.ResourceKey.ResourceClusterAndPoolKey;
import org.bbottema.genericobjectpool.expirypolicies.TimeoutSinceCreationExpirationPolicy;
import org.bbottema.genericobjectpool.util.Timeout;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResourceClustersClusterConfigTest {

	@Test
	void registeredClusterConfigProvidesDefaultsForPoolsInThatCluster() throws Exception {
		ResourceClusters<UUID, String, String> clusters = new ResourceClusters<>(clusterConfig(0, 4, 1000));
		UUID clusterA = UUID.randomUUID();
		UUID clusterB = UUID.randomUUID();

		try {
			clusters.registerResourceCluster(clusterA, clusterConfig(1, 1, 100));
			clusters.registerResourceCluster(clusterB, clusterConfig(3, 3, 200));

			clusters.registerResourcePool(new ResourceClusterAndPoolKey<>(clusterA, "server_A"));
			clusters.registerResourcePool(new ResourceClusterAndPoolKey<>(clusterB, "server_B"));

			assertThat(waitUntilAllocated(clusters, 4, 1000)).isTrue();
			assertThat(clusters.getClusterConfig(clusterA).getDefaultCorePoolSize()).isEqualTo(1);
			assertThat(clusters.getClusterConfig(clusterA).getDefaultMaxPoolSize()).isEqualTo(1);
			assertThat(clusters.getClusterConfig(clusterA).getClaimTimeout().getDurationMs()).isEqualTo(100);
			assertThat(clusters.getClusterConfig(clusterB).getDefaultCorePoolSize()).isEqualTo(3);
			assertThat(clusters.getClusterConfig(clusterB).getDefaultMaxPoolSize()).isEqualTo(3);
			assertThat(clusters.getClusterConfig(clusterB).getClaimTimeout().getDurationMs()).isEqualTo(200);
		} finally {
			waitFor(clusters.shutDown());
		}
	}

	@Test
	void registeredClusterConfigCannotReplaceExistingCluster() throws Exception {
		ResourceClusters<UUID, String, String> clusters = new ResourceClusters<>(clusterConfig(0, 4, 1000));
		UUID clusterKey = UUID.randomUUID();

		try {
			clusters.registerResourceCluster(clusterKey, clusterConfig(1, 1, 100));

			assertThatThrownBy(() -> clusters.registerResourceCluster(clusterKey, clusterConfig(2, 2, 200)))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("Cluster already exists");
		} finally {
			waitFor(clusters.shutDown());
		}
	}

	private static ClusterConfig<UUID, String, String> clusterConfig(int corePoolSize, int maxPoolSize, long claimTimeoutMillis) {
		return ClusterConfig.<UUID, String, String>builder()
				.allocatorFactory(new RoundRobinResourceClustersTestBase.DummyAllocatorFactory())
				.defaultExpirationPolicy(new TimeoutSinceCreationExpirationPolicy<>(10, SECONDS))
				.defaultCorePoolSize(corePoolSize)
				.defaultMaxPoolSize(maxPoolSize)
				.claimTimeout(new Timeout(claimTimeoutMillis, MILLISECONDS))
				.build();
	}

	private static boolean waitUntilAllocated(ResourceClusters<UUID, String, String> clusters, int expectedAllocated, int timeoutMs) {
		int sleptMs = 0;
		while (sleptMs < timeoutMs) {
			if (clusters.countLiveResources() == expectedAllocated) {
				return true;
			}
			try {
				TimeUnit.MILLISECONDS.sleep(25);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
			sleptMs += 25;
		}
		return false;
	}

	private static void waitFor(Future<?> future) throws Exception {
		future.get();
	}
}
