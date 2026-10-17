package com.aram.backend.catalog;

import com.aram.backend.exception.InvalidRequestException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgeMathTest {

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-14T12:00:00Z"), ZoneOffset.UTC);

	@Test
	void ageInMonthsCountsWholeCalendarMonthsFromTheBirthMonth() {
		assertThat(AgeMath.ageMonths(YearMonth.of(2022, 10), CLOCK)).isEqualTo(48);
		assertThat(AgeMath.ageMonths(YearMonth.of(2022, 11), CLOCK)).isEqualTo(47);
		assertThat(AgeMath.ageMonths(YearMonth.of(2026, 10), CLOCK)).isZero();
	}

	@Test
	void aRangeInWholeYearsCoversUpToTheDayBeforeTheNextBirthdayAfterTheLastYear() {
		assertThat(AgeMath.minMonthsForYears(2)).isEqualTo(24);
		assertThat(AgeMath.maxMonthsForYears(4)).isEqualTo(59);
		assertThat(AgeMath.wholeYears(59)).isEqualTo(4);
		assertThat(AgeMath.toWholeYearsOrNull(null)).isNull();
	}

	@Test
	void aBirthMonthMustBeAPastMonthWithinEighteenYears() {
		assertThat(AgeMath.parseBirth("2022-03", CLOCK)).isEqualTo(YearMonth.of(2022, 3));
		assertThat(AgeMath.parseBirth(" 2026-10 ", CLOCK)).isEqualTo(YearMonth.of(2026, 10));
		for (String bad : new String[] { null, "", "2022", "03-2022", "2022-13", "2026-11", "2005-01", "abc" }) {
			assertThatThrownBy(() -> AgeMath.parseBirth(bad, CLOCK)).as(String.valueOf(bad))
					.isInstanceOf(InvalidRequestException.class);
		}
	}
}
