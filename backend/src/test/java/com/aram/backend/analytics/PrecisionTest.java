package com.aram.backend.analytics;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class PrecisionTest {

	@Test
	void ageBandsHaveTheExpectedBoundaries() {
		assertThat(AgeBands.of(0)).isEqualTo("<1y");
		assertThat(AgeBands.of(11)).isEqualTo("<1y");
		assertThat(AgeBands.of(12)).isEqualTo("1-2y");
		assertThat(AgeBands.of(50)).isEqualTo("4-5y");
		assertThat(AgeBands.of(59)).isEqualTo("4-5y");
		assertThat(AgeBands.of(60)).isEqualTo("5-6y");
		assertThat(AgeBands.of(72)).isEqualTo("6-8y");
		assertThat(AgeBands.of(95)).isEqualTo("6-8y");
		assertThat(AgeBands.of(96)).isEqualTo("8-12y");
		assertThat(AgeBands.of(143)).isEqualTo("8-12y");
		assertThat(AgeBands.of(144)).isEqualTo("12-18y");
		assertThat(AgeBands.of(215)).isEqualTo("12-18y");
		assertThat(AgeBands.of(216)).isEqualTo("18y+");
		assertThat(AgeBands.of(-1)).isEqualTo("unknown");
	}

	@Test
	void timestampsAreReducedToAMonthADayOrAnHour() {
		Instant moment = Instant.parse("2026-10-14T13:47:59.123Z");

		assertThat(TimePrecision.month(moment)).isEqualTo(LocalDate.of(2026, 10, 1));
		assertThat(TimePrecision.day(moment)).isEqualTo(LocalDate.of(2026, 10, 14));
		assertThat(TimePrecision.hour(moment)).isEqualTo(Instant.parse("2026-10-14T13:00:00Z"));
		assertThat(TimePrecision.minute(moment)).isEqualTo(Instant.parse("2026-10-14T13:47:00Z"));
		assertThat(TimePrecision.month(null)).isNull();
		assertThat(TimePrecision.hour(null)).isNull();
	}

	@Test
	void aLateNightUtcTimestampStaysOnItsUtcDay() {
		assertThat(TimePrecision.day(Instant.parse("2026-10-31T23:59:59Z"))).isEqualTo(LocalDate.of(2026, 10, 31));
		assertThat(TimePrecision.month(Instant.parse("2026-10-31T23:59:59Z"))).isEqualTo(LocalDate.of(2026, 10, 1));
	}
}
