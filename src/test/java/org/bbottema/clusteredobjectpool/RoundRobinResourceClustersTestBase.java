package org.bbottema.clusteredobjectpool;

import org.bbottema.clusteredobjectpool.core.ResourceClusters;
import org.bbottema.clusteredobjectpool.core.api.AllocatorFactory;
import org.bbottema.clusteredobjectpool.core.api.ResourceKey.ResourceClusterAndPoolKey;
import org.bbottema.genericobjectpool.Allocator;
import org.bbottema.genericobjectpool.PoolableObject;
import org.jetbrains.annotations.NotNull;
import org.junit.Before;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

public abstract class RoundRobinResourceClustersTestBase {
	
	static final int MAX_POOL_SIZE = 4;
	
	ResourceClusters<UUID, String, String> clusters;
	List<PoolableObject<String>> claimedResources;
	
	@Before
	public final void setup() throws InterruptedException {
		claimedResources = new ArrayList<>();
		clusters = createTestableClusters();
		TimeUnit.MILLISECONDS.sleep(100);
	}
	
	protected abstract ResourceClusters<UUID, String, String> createTestableClusters();
	
	@SuppressWarnings("SameParameterValue")
	String claimAndReleaseForKey(UUID keyCluster, String poolKey) throws InterruptedException {
		PoolableObject<String> poolable = clusters.claimResourceFromPool(new ResourceClusterAndPoolKey<>(keyCluster, poolKey));
		requireNonNull(poolable).release();
		return poolable.getAllocatedObject();
	}
	
	@SuppressWarnings("SameParameterValue")
	String claimAndNoReleaseForKey(UUID keyCluster, String poolKey) throws InterruptedException {
		final PoolableObject<String> poolable = clusters.claimResourceFromPool(new ResourceClusterAndPoolKey<>(keyCluster, poolKey));
		claimedResources.add(requireNonNull(poolable));
		return poolable.getAllocatedObject();
	}
	
	String claimAndReleaseResource(UUID keyCluster) throws InterruptedException {
		PoolableObject<String> poolable = clusters.claimResourceFromCluster(keyCluster);
		requireNonNull(poolable).release();
		return poolable.getAllocatedObject();
	}
	
	String claimAndNoReleaseResource(UUID keyCluster) throws InterruptedException {
		PoolableObject<String> poolable = clusters.claimResourceFromCluster(keyCluster);
		claimedResources.add(requireNonNull(poolable));
		return poolable.getAllocatedObject();
	}
	
	public static class DummyAllocatorFactory implements AllocatorFactory<String, String> {
		private static final Logger LOGGER = LoggerFactory.getLogger(DummyAllocatorFactory.class);
		@NotNull
		@Override
		public Allocator<String> create(@NotNull final String serverInfo) {
			return new Allocator<String>() {
				private int counter = 0;
				
				@NotNull
				@Override
				public String allocate() {
					String s = format("connection%s%d", serverInfo.substring(serverInfo.indexOf('_')), ++counter);
					LOGGER.debug("allocating " + s);
					return s;
				}
				
				@Override
				public void deallocate(@NotNull String object) {
					LOGGER.debug("deallocating " + object);
				}
			};
		}
	}
}