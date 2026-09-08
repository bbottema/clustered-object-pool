package org.bbottema.clusteredobjectpool.core;

import org.bbottema.clusteredobjectpool.util.CompositeFuturesAsFutureTask;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeferredPoolShutdownTest {
	@Test
	void failedPoolDoesNotFinishCompositeShutdownBeforeItsNeighbours() throws Exception {
		final CompletableFuture<Void> failed = new CompletableFuture<>();
		final CompletableFuture<Void> pending = new CompletableFuture<>();
		final IllegalStateException cause = new IllegalStateException("first disposal failed");
		final AtomicBoolean forgotten = new AtomicBoolean();
		failed.completeExceptionally(cause);
		final Future<Void> composite = CompositeFuturesAsFutureTask.ofFutures(Arrays.asList(failed, pending), () -> forgotten.set(true));
		try {
			assertThatThrownBy(() -> composite.get(20, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
			assertThat(forgotten.get()).isFalse();
		} finally {
			pending.complete(null);
		}
		assertThatThrownBy(() -> composite.get(1, TimeUnit.SECONDS)).hasRootCause(cause);
		assertThat(forgotten.get()).isTrue();
	}

	@Test
	void retirementRequiresInitializationAndFinalCleanup() throws Exception {
		final DeferredPoolShutdown retirement = new DeferredPoolShutdown();
		assertThat(retirement.cancel(true)).isFalse();
		assertThat(retirement.isDone()).isFalse();
		assertThatThrownBy(() -> retirement.get(1, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
		final CompletableFuture<Void> disposal = new CompletableFuture<>();
		retirement.awaitDisposal(disposal);
		assertThat(retirement.isDone()).isFalse();
		disposal.complete(null);
		retirement.get(1, TimeUnit.SECONDS);
		assertThat(retirement.isDone()).isTrue();
	}

	@Test
	void compositeShutdownPreservesAnUnderlyingFailure() {
		final CompletableFuture<Void> failed = new CompletableFuture<>();
		final IllegalStateException cause = new IllegalStateException("cleanup failed");
		failed.completeExceptionally(cause);
		final Future<Void> composite = CompositeFuturesAsFutureTask.ofFutures(Collections.singletonList(failed));
		assertThatThrownBy(() -> composite.get(1, TimeUnit.SECONDS)).hasRootCause(cause);
	}
}
