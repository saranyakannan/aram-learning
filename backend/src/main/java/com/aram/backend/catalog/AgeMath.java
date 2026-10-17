package com.aram.backend.catalog;

import com.aram.backend.exception.InvalidRequestException;

import java.time.DateTimeException;
import java.time.Clock;
import java.time.YearMonth;

/** Age arithmetic in months. A child's age is always computed from their birth month, never stored. */
public final class AgeMath {

	public static final int MAX_AGE_YEARS = 18;

	private AgeMath() {
	}

	public static YearMonth parseBirth(String birthYearMonth, Clock clock) {
		YearMonth birth;
		try {
			birth = YearMonth.parse(birthYearMonth == null ? "" : birthYearMonth.trim());
		} catch (DateTimeException e) {
			throw new InvalidRequestException("Birth month must look like 2022-03");
		}
		YearMonth now = YearMonth.now(clock);
		if (birth.isAfter(now)) {
			throw new InvalidRequestException("Birth month can't be in the future");
		}
		if (ageMonths(birth, clock) >= (MAX_AGE_YEARS + 1) * 12) {
			throw new InvalidRequestException("Child age must be between 0 and " + MAX_AGE_YEARS);
		}
		return birth;
	}

	public static int ageMonths(YearMonth birth, Clock clock) {
		return (int) birth.until(YearMonth.now(clock), java.time.temporal.ChronoUnit.MONTHS);
	}

	public static int wholeYears(int ageMonths) {
		return ageMonths / 12;
	}

	/** A range given in whole years (2 to 4) covers from the 2nd birthday up to the day before the 5th. */
	public static int minMonthsForYears(int years) {
		return years * 12;
	}

	public static int maxMonthsForYears(int years) {
		return years * 12 + 11;
	}

	public static Integer toWholeYearsOrNull(Integer months) {
		return months == null ? null : months / 12;
	}
}
