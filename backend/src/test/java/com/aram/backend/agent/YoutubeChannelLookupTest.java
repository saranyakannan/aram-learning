package com.aram.backend.agent;

import com.aram.backend.model.ChannelFacts;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class YoutubeChannelLookupTest {

	private static ChannelFacts channel(String id, String title) {
		return new ChannelFacts(id, title, null, "", null, null, null);
	}

	@Test
	void singleSameTitleResultIsTheExactMatchEvenWithPunctuationAndCaseDifferences() {
		var outcome = YoutubeChannelLookup.outcomeFor("ms rachel",
				List.of(channel("A", "Songs for Littles"), channel("B", "Ms. Rachel"), channel("C", "Rachel's Kitchen")));

		assertThat(outcome.exact().channelId()).isEqualTo("B");
		assertThat(outcome.candidates()).extracting(ChannelFacts::channelId).containsExactly("B");
	}

	@Test
	void twoChannelsWithTheSameTitleAreNeverAutoPicked() {
		var outcome = YoutubeChannelLookup.outcomeFor("Bluey",
				List.of(channel("OFFICIAL", "Bluey"), channel("FAKE", "Bluey"), channel("X", "Bluey Fans")));

		assertThat(outcome.exact()).isNull();
		assertThat(outcome.candidates()).extracting(ChannelFacts::channelId).containsExactly("OFFICIAL", "FAKE", "X");
	}

	@Test
	void withoutATitleMatchTheTopThreeInSearchOrderAreOffered() {
		var outcome = YoutubeChannelLookup.outcomeFor("space show for kids",
				List.of(channel("1", "Space Kids"), channel("2", "Kids Space TV"), channel("3", "Cosmos Jr"), channel("4", "Other")));

		assertThat(outcome.exact()).isNull();
		assertThat(outcome.candidates()).extracting(ChannelFacts::channelId).containsExactly("1", "2", "3");
	}

	@Test
	void noResultsGivesAnEmptyOutcome() {
		var outcome = YoutubeChannelLookup.outcomeFor("zzzz", List.of());

		assertThat(outcome.exact()).isNull();
		assertThat(outcome.candidates()).isEmpty();
	}
}
