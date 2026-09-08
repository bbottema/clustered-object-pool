package consumer;

import org.bbottema.clusteredobjectpool.core.ClusterConfig;
import org.bbottema.clusteredobjectpool.core.ResourceClusters;
import org.bbottema.clusteredobjectpool.core.api.ResourceKey.ResourceClusterAndPoolKey;
import org.bbottema.genericobjectpool.AllocationContext;
import org.bbottema.genericobjectpool.Allocator;
import org.bbottema.genericobjectpool.ClaimControl;
import org.bbottema.genericobjectpool.ClaimOptions;
import org.bbottema.genericobjectpool.PoolableObject;

import java.util.concurrent.TimeUnit;

/** Exercises opt-in cluster acquisition on both classpath and module path. */
public final class ClusterApiConsumer {
	public static void main(final String[] args) throws Exception {
		final ResourceClusters<String, String, String> clusters = new ResourceClusters<>(ClusterConfig.<String, String, String>builder()
				.allocatorFactory(key -> new Allocator<String>() {
					@Override public String allocate() { return "legacy"; }
					@Override public String allocate(final AllocationContext context) { return "controlled"; }
				}).defaultMaxPoolSize(1).defaultExpirationPolicy(value -> false).build());
		final ClaimControl control = new ClaimControl();
		try {
			final PoolableObject<String> resource = clusters.claimResourceFromPool(new ResourceClusterAndPoolKey<>("cluster", "pool"),
					ClaimOptions.withTimeout(2, TimeUnit.SECONDS).withClaimControl(control));
			if (resource == null || !"controlled".equals(resource.getAllocatedObject())) {
				throw new AssertionError("Controlled cluster client failed");
			}
			control.requestCancellation();
			resource.release();
		} finally {
			clusters.shutDown().get(5, TimeUnit.SECONDS);
		}
		System.out.println("Opt-in cluster acquisition API: OK");
	}
}
