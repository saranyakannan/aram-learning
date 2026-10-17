package com.aram.backend.podcast;

import com.aram.backend.exception.PodcastLookupException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PodcastFeedParserTest {

	private static final String HEAD = """
			<?xml version="1.0" encoding="UTF-8"?>
			<rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
			<channel>
			  <title>Wow &amp; Wonder</title>
			  <description><![CDATA[<p>Science for <b>curious</b> kids &amp; grown-ups.</p>]]></description>
			  <itunes:explicit>no</itunes:explicit>
			  <itunes:image href="https://img.example.com/show.jpg"/>
			  <image><title>Not the show title</title><url>https://img.example.com/x.png</url></image>
			""";

	private static String item(String title, String guid, String url, String extra) {
		return "<item><title>" + title + "</title><description><![CDATA[<p>About " + title + "</p>]]></description>"
				+ "<guid isPermaLink=\"false\">" + guid + "</guid><pubDate>Mon, 05 Oct 2026 05:00:00 -0000</pubDate>"
				+ "<itunes:duration>12:34</itunes:duration>"
				+ (url == null ? "" : "<enclosure url=\"" + url + "\" type=\"audio/mpeg\" length=\"123\"/>") + extra + "</item>";
	}

	private static byte[] xml(String... items) {
		return (HEAD + String.join("\n", items) + "</channel></rss>").getBytes(StandardCharsets.UTF_8);
	}

	@Test
	void itReadsTheShowAndItsEpisodes() {
		PodcastFeedParser.Feed feed = PodcastFeedParser.parse(
				xml(item("Episode 1", "g-1", "https://cdn.example.com/1.mp3", ""),
						item("Episode 2", "g-2", "http://cdn.example.com/2.mp3", "")), 10);

		assertThat(feed.title()).isEqualTo("Wow & Wonder");
		assertThat(feed.description()).isEqualTo("Science for curious kids & grown-ups.");
		assertThat(feed.explicit()).isFalse();
		assertThat(feed.imageUrl()).isEqualTo("https://img.example.com/show.jpg");
		assertThat(feed.episodes()).extracting(PodcastEpisode::title).containsExactly("Episode 1", "Episode 2");
		PodcastEpisode first = feed.episodes().get(0);
		assertThat(first.audioUrl()).isEqualTo("https://cdn.example.com/1.mp3");
		assertThat(first.audioType()).isEqualTo("audio/mpeg");
		assertThat(first.durationSeconds()).isEqualTo(12 * 60 + 34);
		assertThat(first.publishedAt()).isEqualTo("2026-10-05T05:00:00Z");
		assertThat(first.description()).isEqualTo("About Episode 1");
		assertThat(first.id()).hasSize(16).matches("[0-9a-f]+");
		assertThat(feed.episodes().get(1).id()).isNotEqualTo(first.id());
	}

	@Test
	void theFeedsDeclaredLanguageIsRead() {
		String withLanguage = new String(xml(), StandardCharsets.UTF_8).replace("<title>Wow &amp; Wonder</title>", "<title>Wow &amp; Wonder</title><language>en-us</language>");

		assertThat(PodcastFeedParser.parse(withLanguage.getBytes(StandardCharsets.UTF_8), 5).language()).isEqualTo("en-us");
		assertThat(PodcastFeedParser.parse(xml(), 5).language()).as("none declared").isNull();
	}

	@Test
	void episodeIdsAreStableAcrossReads() {
		byte[] bytes = xml(item("Episode 1", "g-1", "https://cdn.example.com/1.mp3", ""));

		assertThat(PodcastFeedParser.parse(bytes, 5).episodes().get(0).id())
				.isEqualTo(PodcastFeedParser.parse(bytes, 5).episodes().get(0).id());
	}

	@Test
	void itStopsAfterTheRequestedNumberOfEpisodes() {
		String[] items = new String[50];
		for (int i = 0; i < items.length; i++) {
			items[i] = item("Episode " + i, "g-" + i, "https://cdn.example.com/" + i + ".mp3", "");
		}

		assertThat(PodcastFeedParser.parse(xml(items), 3).episodes()).hasSize(3);
	}

	@Test
	void episodesTheShowMarksExplicitOrWithoutPlayableHttpAudioAreDropped() {
		PodcastFeedParser.Feed feed = PodcastFeedParser.parse(xml(
				item("Clean", "g-1", "https://cdn.example.com/1.mp3", ""),
				item("Explicit", "g-2", "https://cdn.example.com/2.mp3", "<itunes:explicit>yes</itunes:explicit>"),
				item("No audio", "g-3", null, ""),
				item("Script link", "g-4", "javascript:alert(1)", ""),
				item("File link", "g-5", "file:///etc/passwd", ""),
				item("Good again", "g-6", "https://cdn.example.com/6.mp3", "")), 10);

		assertThat(feed.episodes()).extracting(PodcastEpisode::title).containsExactly("Clean", "Good again");
	}

	@Test
	void aShowMarkedExplicitIsReportedAsSo() {
		String text = new String(xml(), StandardCharsets.UTF_8).replace("<itunes:explicit>no<", "<itunes:explicit>yes<");

		assertThat(PodcastFeedParser.parse(text.getBytes(StandardCharsets.UTF_8), 5).explicit()).isTrue();
	}

	@Test
	void markupInFeedTextIsStrippedAndLongTextIsShortened() {
		PodcastFeedParser.Feed feed = PodcastFeedParser.parse(xml(
				item("<script>alert(1)</script>Title", "g-1", "https://cdn.example.com/1.mp3", "")
						.replace("About", "x".repeat(900))), 5);

		PodcastEpisode episode = feed.episodes().get(0);
		assertThat(episode.title()).doesNotContain("<").contains("Title");
		assertThat(episode.description().length()).isLessThanOrEqualTo(500);
	}

	@Test
	void aFeedCutOffBySizeStillYieldsTheEpisodesBeforeTheCut() {
		byte[] full = xml(item("Episode 1", "g-1", "https://cdn.example.com/1.mp3", ""),
				item("Episode 2", "g-2", "https://cdn.example.com/2.mp3", ""));
		String text = new String(full, StandardCharsets.UTF_8);
		byte[] cut = text.substring(0, text.indexOf("<item><title>Episode 2") + 30).getBytes(StandardCharsets.UTF_8);

		assertThat(PodcastFeedParser.parse(cut, 10).episodes()).extracting(PodcastEpisode::title).containsExactly("Episode 1");
	}

	@Test
	void externalEntitiesAndDtdsAreNotResolved() {
		String attack = """
				<?xml version="1.0"?>
				<!DOCTYPE rss [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
				<rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"><channel><title>&xxe;</title>
				<item><title>Safe</title><enclosure url="https://cdn.example.com/1.mp3" type="audio/mpeg"/></item></channel></rss>""";

		String result;
		try {
			PodcastFeedParser.Feed feed = PodcastFeedParser.parse(attack.getBytes(StandardCharsets.UTF_8), 5);
			result = String.valueOf(feed.title()) + feed.episodes();
		} catch (PodcastLookupException refused) {
			result = "refused";
		}
		assertThat(result).doesNotContain("root:");
	}

	@Test
	void garbageIsRefusedWithAMessage() {
		assertThatThrownBy(() -> PodcastFeedParser.parse("not xml at all".getBytes(StandardCharsets.UTF_8), 5))
				.isInstanceOf(PodcastLookupException.class);
		assertThatThrownBy(() -> PodcastFeedParser.parse(new byte[0], 5)).isInstanceOf(PodcastLookupException.class);
	}

	@Test
	void durationsAcceptHoursMinutesOrPlainSeconds() {
		assertThat(PodcastFeedParser.parseDuration("1:02:03")).isEqualTo(3723);
		assertThat(PodcastFeedParser.parseDuration("45:10")).isEqualTo(2710);
		assertThat(PodcastFeedParser.parseDuration("90")).isEqualTo(90);
		assertThat(PodcastFeedParser.parseDuration("soon")).isNull();
		assertThat(PodcastFeedParser.parseDuration(null)).isNull();
	}

	@Test
	void onlyWebAddressesAreKeptAsUrls() {
		assertThat(PodcastFeedParser.httpUrlOrNull("https://a.example.com/x.mp3")).isNotNull();
		assertThat(PodcastFeedParser.httpUrlOrNull("data:text/html,x")).isNull();
		assertThat(PodcastFeedParser.httpUrlOrNull("//a.example.com/x")).isNull();
		assertThat(PodcastFeedParser.httpUrlOrNull(" ")).isNull();
		assertThat(PodcastFeedParser.httpUrlOrNull("https://a.example.com/" + "x".repeat(2100))).isNull();
		assertThat(List.of(PodcastFeedParser.isTrue("Yes"), PodcastFeedParser.isTrue("clean"))).containsExactly(true, false);
	}
}
