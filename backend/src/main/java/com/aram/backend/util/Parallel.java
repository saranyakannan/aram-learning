package com.aram.backend.util;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Runs independent blocking lookups (one Firestore cache read or feed fetch per channel) side by side instead of one after
 * another, so a page that needs 20 of them waits for about the slowest one rather than the sum. Results keep the input order.
 * A work function that throws fails the whole call with that same exception, so callers catch per-item failures inside it.
 */
public final class Parallel {

	private static final AtomicInteger COUNTER = new AtomicInteger();
	private static final ExecutorService POOL = Executors.newFixedThreadPool(8, task -> {
		Thread thread = new Thread(task, "aram-io-" + COUNTER.incrementAndGet());
		thread.setDaemon(true);
		return thread;
	});

	private Parallel() {
	}

	public static <T, R> List<R> map(List<T> items, Function<T, R> work) {
		if (items.size() <= 1) {
			return items.stream().map(work).toList();
		}
		List<CompletableFuture<R>> futures = items.stream()
				.map(item -> CompletableFuture.supplyAsync(() -> work.apply(item), POOL)).toList();
		try {
			return futures.stream().map(CompletableFuture::join).toList();
		} catch (CompletionException e) {
			futures.forEach(future -> future.cancel(true));
			if (e.getCause() instanceof RuntimeException runtime) {
				throw runtime;
			}
			throw e;
		}
	}
}
