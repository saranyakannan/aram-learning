package com.aram.backend.podcast;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PodcastLinkParserTest {

	private final PodcastLinkParser parser = new PodcastLinkParser();

	@Test
	void anAppleLinkYieldsTheShowIdWhateverTheCountryOrSlug() {
		assertThat(parser.parse("https://podcasts.apple.com/us/podcast/wow-in-the-world/id1233834541").appleId()).isEqualTo(1233834541L);
		assertThat(parser.parse("https://podcasts.apple.com/in/podcast/id1233834541?i=1000600000").appleId()).isEqualTo(1233834541L);
		assertThat(parser.parse("podcasts.apple.com/us/podcast/x/id719585944").appleId()).isEqualTo(719585944L);
		assertThat(parser.parse("https://itunes.apple.com/us/podcast/x/id703720228?mt=2").appleId()).isEqualTo(703720228L);
		assertThat(parser.parse("https://itunes.apple.com/lookup?id=1103320303").appleId()).isEqualTo(1103320303L);
	}

	@Test
	void aBareIdOrAPlainNameIsAccepted() {
		assertThat(parser.parse("1233834541").appleId()).isEqualTo(1233834541L);
		assertThat(parser.parse("  Wow in the World ").name()).isEqualTo("Wow in the World");
	}

	@Test
	void spotifyAndOtherSitesAreRefusedWithAClearReason() {
		assertThat(parser.parse("https://open.spotify.com/show/abc123").error()).contains("Spotify");
		assertThat(parser.parse("https://example.com/feed.xml").error()).contains("Apple Podcasts");
		assertThat(parser.parse("https://podcasts.apple.com/us/browse").error()).contains("couldn't find");
	}

	@Test
	void emptyOrTooLongInputIsRejected() {
		assertThat(parser.parse(null).error()).isNotNull();
		assertThat(parser.parse("   ").error()).isNotNull();
		assertThat(parser.parse("x".repeat(PodcastLinkParser.MAX_NAME_LENGTH + 1)).error()).contains("too long");
	}
}
