package com.aram.backend.agent;

import com.aram.backend.exception.RateLimitException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Sliding-window limit per parent so a runaway client cannot burn Gemini or YouTube quota. */
@Component
public class AgentRateLimiter {

	static final int MAX_REQUESTS = 15;
	static final long WINDOW_MILLIS = 60_000;

	private final Clock clock;
	private final Map<String, Deque<Long>> recent = new ConcurrentHashMap<>();

	public AgentRateLimiter() {
		this(Clock.systemUTC());
	}

	AgentRateLimiter(Clock clock) {
		this.clock = clock;
	}

	public void check(String key) {
		long now = clock.millis();
		Deque<Long> times = recent.computeIfAbsent(key, k -> new ArrayDeque<>());
		synchronized (times) {
			while (!times.isEmpty() && now - times.peekFirst() >= WINDOW_MILLIS) {
				times.pollFirst();
			}
			if (times.size() >= MAX_REQUESTS) {
				throw new RateLimitException("You're going a little fast. Please wait a minute and try again.");
			}
			times.addLast(now);
		}
	}
}
