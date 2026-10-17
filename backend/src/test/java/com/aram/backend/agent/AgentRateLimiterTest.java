package com.aram.backend.agent;

import com.aram.backend.exception.RateLimitException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentRateLimiterTest {

	private static class MutableClock extends Clock {
		private Instant now = Instant.parse("2026-10-14T12:00:00Z");

		void advanceMillis(long millis) {
			now = now.plusMillis(millis);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}

	@Test
	void blocksTheRequestAfterTheLimitAndRecoversWhenTheWindowPasses() {
		MutableClock clock = new MutableClock();
		AgentRateLimiter limiter = new AgentRateLimiter(clock);

		for (int i = 0; i < AgentRateLimiter.MAX_REQUESTS; i++) {
			limiter.check("parent-a");
		}
		assertThatThrownBy(() -> limiter.check("parent-a")).isInstanceOf(RateLimitException.class);

		clock.advanceMillis(AgentRateLimiter.WINDOW_MILLIS);
		assertThatCode(() -> limiter.check("parent-a")).doesNotThrowAnyException();
	}

	@Test
	void parentsAreLimitedIndependently() {
		AgentRateLimiter limiter = new AgentRateLimiter(new MutableClock());
		for (int i = 0; i < AgentRateLimiter.MAX_REQUESTS; i++) {
			limiter.check("parent-a");
		}

		assertThatCode(() -> limiter.check("parent-b")).doesNotThrowAnyException();
	}
}
