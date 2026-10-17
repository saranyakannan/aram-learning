package com.aram.backend.analytics;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/** Reduces timestamp precision: family data is kept to a month or a day, assessments to the hour (UTC). */
public final class TimePrecision {

	private TimePrecision() {
	}

	/** First day of the month. */
	public static LocalDate month(Instant instant) {
		return instant == null ? null : instant.atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1);
	}

	public static LocalDate day(Instant instant) {
		return instant == null ? null : instant.atZone(ZoneOffset.UTC).toLocalDate();
	}

	public static Instant hour(Instant instant) {
		return instant == null ? null : instant.truncatedTo(ChronoUnit.HOURS);
	}

	public static Instant minute(Instant instant) {
		return instant == null ? null : instant.truncatedTo(ChronoUnit.MINUTES);
	}
}
