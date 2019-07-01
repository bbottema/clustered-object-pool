# generic-object-pool

clustered-object-pool is a lightweight generic clustering object pool, building on generic-object-pool and adding
keyed clustered pools. 

[API Documentation](https://www.javadoc.io/doc/com.github.bbottema/clustered-object-pool/1.0.0)

## Setup

Maven Dependency Setup

```xml
<dependency>
	<groupId>com.github.bbottema</groupId>
	<artifactId>clustered-object-pool</artifactId>
	<version>1.0.3</version>
</dependency>
```

## Usage

This library builds on top of [generic-object-pool](https://github.com/bbottema/generic-object-pool), which means support for eager/lazy loading and auto expiring objects.

There are a couple of scenario's you can solve with clustered-object-pool:
- Have **1 cluster with 1 pool of size 1**. Same as using generic-object-pool directly, where you have one resource, but can share/reuse it among threads.
- Have **1 cluster with 1 pool of size n**. Same as using generic-object-pool directly, where multiple resources are shared/reused among threads.
- Have **1 cluster with n pools of size 1**. If you have one cluster with rotating pools to draw a shareable/reusable object from. Usefull when you want to spread load around different servers.
- Have **1 cluster with n pools of size n**. Same as above, except with multiple objects. For example multiple connections to multiple servers. 
- Have **n clusters ....** Same as all the above except you have dedicated clusters for different purposes. For example a cluster for handling internal mails and a cluster for outgoing mails. 

When creating clusters, you can optionally preregister pools with specific behavior. 
If you don't pools and clusters and pools are created on the fly with the provided defaults.

#### Creating clusters

These examples illustrates this library using SMTP clustering connection pools, 
for which there is actually a dedicated micro library, [smtp-connection-pool](https://github.com/simple-java-mail/smtp-connection-pool)

```java
// basic pool with no eager loading and no expiry policy and default round robin pool rotation
ClusterConfig<Session, Transport> clusterConfig = ClusterConfig.<Session, Transport>builder()
    .allocatorFactory(allocatorFactory)
    .defaultMaxPoolSize(defaultMaxPoolSize)
    .build();

ResourceClusters<UUID, Session, Transport> clusters = new ResourceClusters<>(clusterConfig);

UUID keyCluster1 = UUID.randomUUID();

// claim from cluster, blocking until a resource becomes available
PoolableObject<Transport> resource = clusters.claimResourceFromCluster(keyCluster1);
// claim from specific pool, blocking until a resource becomes available
PoolableObject<Transport> resource = clusters.claimResourceFromPool(new ResourceClusterAndPoolKey<>(keyCluster1, SessionToServerA));

// above cluster and pools are created on the fly
```

The following cycling strategies are provided by default:
- RoundRobinCyclingStrategy
- RandomAccessCyclingStrategy

#### Customizing pools

```java
// the following has the same behavior as not prergistering and registering on the fly when claiming resources
// it simply uses the global defaults
clusters.registerResourcePool(new ResourceClusterAndPoolKey<>(keyCluster1, SessionForServerA));
clusters.registerResourcePool(new ResourceClusterAndPoolKey<>(keyCluster1, SessionForServerB));

// but you can also change individual pool behavior (enabling all the aforementioned scenario's):
clusters.registerResourcePool(new ResourceClusterAndPoolKey<>(keyCluster1, SessionForServerA),
    new SpreadedTimeoutSinceLastAllocationExpirationPolicy(5, 10, TimeUnit.SECONDS),
    10, // core pool size, which means eagerly loading
    20); // max pool size
clusters.registerResourcePool(new ResourceClusterAndPoolKey<>(keyCluster1, SessionForServerB));
````

The above example creates clusters and pools on the fly as resources are claimed with the appropriate keys, 
except for serverA in cluster1: for this server, 10 connections are preloaded with 10 max connections allowed at
busy times and with auto expiring connections, disconnection spreading between 5 to 10 seconds after a connection was last used.

#### Providing objects for the clustered pools

Normally you would provide an Allocator to the underlying generic-object-pool, now you provide an AllocatorFactory for
when a new pool is being created.

For SMTP connections, here's a possible implementation:

```java
class TransportAllocatorFactory implements AllocatorFactory<Session, Transport> {
	@Override
	public Allocator<Transport> create(Session session) {
		return new TransportAllocator(session);
	}
}

class TransportAllocator extends Allocator<Transport> {

	private static final Logger LOGGER = getLogger(TransportAllocator.class);

	private final Session session;

	TransportAllocator(final Session session) {
		this.session = session;
	}

	@Override
	public Transport allocate() {
		LOGGER.trace("opening transport connection...");
		try {
			return session.getTransport();
		} catch (NoSuchProviderException e) {
			throw new RuntimeException("unable to get transport from session", e);
		}
	}
	
	@Override
	public void deallocate(Transport transport) {
		LOGGER.trace("closing transport...");
		try {
			transport.close();
		} catch (MessagingException e) {
			throw new RuntimeException("error closing transport connection", e);
		}
	}
}
```
Now each time a specific pool created -either by claiming for it or pre-registering-, this factory is invoked
with the respective pool key (the Session instance representing the server). 