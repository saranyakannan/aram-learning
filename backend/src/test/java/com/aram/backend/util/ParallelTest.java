package com.aram.backend.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParallelTest {

	@Test
	void resultsComeBackInTheOrderOfTheInput() {
		assertThat(Parallel.map(List.of(5, 1, 4, 2, 3), n -> {
			try {
				Thread.sleep(n * 10L);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return "item" + n;
		})).containsExactly("item5", "item1", "item4", "item2", "item3");
	}

	@Test
	void theWorkReallyRunsAtTheSameTime() throws Exception {
		// Four tasks that each wait for all four to have started can only finish if they run side by side.
		CountDownLatch allStarted = new CountDownLatch(4);
		AtomicInteger finished = new AtomicInteger();
		List<Boolean> results = Parallel.map(List.of(1, 2, 3, 4), n -> {
			allStarted.countDown();
			try {
				boolean together = allStarted.await(5, TimeUnit.SECONDS);
				finished.incrementAndGet();
				return together;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
		});

		assertThat(results).containsOnly(true);
		assertThat(finished.get()).isEqualTo(4);
	}

	@Test
	void aFailureInOneItemSurfacesAsThatSameException() {
		assertThatThrownBy(() -> Parallel.map(List.of(1, 2, 3), n -> {
			if (n == 2) {
				throw new IllegalStateException("boom");
			}
			return n;
		})).isInstanceOf(IllegalStateException.class).hasMessage("boom");
	}

	@Test
	void anEmptyOrSingleItemListNeedsNoThreads() {
		assertThat(Parallel.map(List.<Integer>of(), n -> n)).isEmpty();
		assertThat(Parallel.map(List.of(7), n -> n * 2)).containsExactly(14);
	}
}
