import org.bbottema.clusteredobjectpool.core.ClusterConfig;
import org.bbottema.clusteredobjectpool.core.ResourceClusters;
import org.bbottema.clusteredobjectpool.core.api.ResourceKey.ResourceClusterAndPoolKey;
import org.bbottema.genericobjectpool.Allocator;
import org.bbottema.genericobjectpool.PoolableObject;

import java.util.concurrent.TimeUnit;

/** Compiled against the released 4.0.4 API, then run unchanged with the candidate. */
public final class LegacyClusterClient {
	public static void main(final String[] args) throws Exception {
		final ResourceClusters<String, String, String> clusters = new ResourceClusters<>(ClusterConfig.<String, String, String>builder()
				.allocatorFactory(key -> new Allocator<String>() {
					@Override public String allocate() { return key.getPoolKey(); }
				}).defaultMaxPoolSize(1).defaultExpirationPolicy(value -> false).build());
		try {
			final PoolableObject<String> resource = clusters.claimResourceFromPool(new ResourceClusterAndPoolKey<>("cluster", "legacy"));
			if (resource == null || !"legacy".equals(resource.getAllocatedObject())) {
				throw new AssertionError("Legacy cluster client failed");
			}
			resource.release();
		} finally {
			clusters.shutDown().get(5, TimeUnit.SECONDS);
		}
		System.out.println("Previously compiled cluster and allocator factory: OK");
	}
}
