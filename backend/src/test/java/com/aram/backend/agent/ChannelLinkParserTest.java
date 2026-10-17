package com.aram.backend.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelLinkParserTest {

	private static final String CHANNEL_ID = "UCEC-8nMiuXYH2QUs0yYcHTQ";
	private final ChannelLinkParser parser = new ChannelLinkParser();

	@ParameterizedTest
	@CsvSource({
			"https://www.youtube.com/@Bluey, @Bluey",
			"youtube.com/@Numberblocks/videos, @Numberblocks",
			"https://m.youtube.com/@SciShowKids?si=abc, @SciShowKids",
			"@NatGeoKids, @NatGeoKids",
	})
	void parsesHandles(String input, String expectedHandle) {
		assertThat(parser.parse(input)).isEqualTo(new ChannelQuery.ByHandle(expectedHandle));
	}

	@Test
	void parsesChannelIdsFromLinksAndBare() {
		assertThat(parser.parse("https://www.youtube.com/channel/" + CHANNEL_ID)).isEqualTo(new ChannelQuery.ById(CHANNEL_ID));
		assertThat(parser.parse(CHANNEL_ID)).isEqualTo(new ChannelQuery.ById(CHANNEL_ID));
	}

	@ParameterizedTest
	@CsvSource({
			"https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=10, dQw4w9WgXcQ",
			"https://youtu.be/dQw4w9WgXcQ?si=x, dQw4w9WgXcQ",
			"https://www.youtube.com/shorts/dQw4w9WgXcQ, dQw4w9WgXcQ",
			"https://www.youtube.com/embed/dQw4w9WgXcQ, dQw4w9WgXcQ",
	})
	void parsesVideoLinksToVideoIds(String input, String videoId) {
		assertThat(parser.parse(input)).isEqualTo(new ChannelQuery.ByVideoId(videoId));
	}

	@Test
	void legacyCustomUrlsAndPlainTextBecomeNames() {
		assertThat(parser.parse("https://www.youtube.com/c/SuperSimpleSongs")).isEqualTo(new ChannelQuery.ByName("SuperSimpleSongs"));
		assertThat(parser.parse("https://www.youtube.com/user/natgeo")).isEqualTo(new ChannelQuery.ByName("natgeo"));
		assertThat(parser.parse("  Nat Geo Kids  ")).isEqualTo(new ChannelQuery.ByName("Nat Geo Kids"));
		assertThat(parser.parse("Ms. Rachel")).isEqualTo(new ChannelQuery.ByName("Ms. Rachel"));
	}

	@ParameterizedTest
	@CsvSource({
			"https://open.spotify.com/show/abc123",
			"https://feeds.megaphone.fm/brainson",
			"https://evil.example.com/watch?v=dQw4w9WgXcQ",
			"https://www.youtube.com.evil.com/@Bluey",
			"https://www.youtube.com/playlist?list=PL123",
			"https://www.youtube.com/watch?v=short",
			"https://www.youtube.com/",
			"@a",
	})
	void rejectsEverythingElse(String input) {
		assertThat(parser.parse(input)).isInstanceOf(ChannelQuery.Unsupported.class);
	}

	@Test
	void blankAndOverlongInputIsRejected() {
		assertThat(parser.parse("   ")).isInstanceOf(ChannelQuery.Unsupported.class);
		assertThat(parser.parse(null)).isInstanceOf(ChannelQuery.Unsupported.class);
		assertThat(parser.parse("x".repeat(101))).isInstanceOf(ChannelQuery.Unsupported.class);
	}
}
