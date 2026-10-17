package com.aram.backend.podcast;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.aram.backend.config.AramProperties;
import com.aram.backend.service.CuratedPodcastCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Talks to the real Apple directory and the real publishers' feeds (no keys, no database). Skipped unless run as
 * {@code ARAM_LIVE_PODCASTS=true ./gradlew test --tests '*PodcastLiveTest' --info}.
 */
@EnabledIfEnvironmentVariable(named = "ARAM_LIVE_PODCASTS", matches = "true")
class PodcastLiveTest {

	private final AramProperties properties = new AramProperties();
	private final SafeHttp http = new SafeHttp(properties);
	private final PodcastDirectory directory = new PodcastDirectory(http, new ObjectMapper(), properties);
	private final PodcastFeedService feeds = new PodcastFeedService(http, null, properties, java.time.Clock.systemUTC());

	@Test
	void everyStarterShowResolvesToItsOwnFeedWithPlayableEpisodes() throws Exception {
		CuratedPodcastCatalog catalog = new CuratedPodcastCatalog(new DefaultResourceLoader(), properties, new ObjectMapper());
		var load = CuratedPodcastCatalog.class.getDeclaredMethod("load");
		load.setAccessible(true);
		load.invoke(catalog);

		for (CuratedPodcastCatalog.CuratedPodcast starter : catalog.getPodcasts()) {
			PodcastFacts show = directory.findById(starter.appleId()).orElseThrow();
			PodcastFeedParser.Feed feed = feeds.fetchFeed(show.feedUrl());
			System.out.println("[live-podcast] " + starter.name() + " -> directory: '" + show.title() + "' by " + show.author()
					+ " | genre " + show.genre() + " | explicit " + show.explicit() + " | feed title '" + feed.title()
					+ "' explicit " + feed.explicit() + " | " + feed.episodes().size() + " episodes, newest '"
					+ (feed.episodes().isEmpty() ? "-" : feed.episodes().get(0).title()) + "' "
					+ (feed.episodes().isEmpty() ? "" : feed.episodes().get(0).publishedAt()));
			assertThat(show.explicit()).as(starter.name()).isFalse();
			assertThat(feed.explicit()).as(starter.name()).isFalse();
			assertThat(feed.episodes()).as(starter.name()).isNotEmpty();
			assertThat(feed.episodes().get(0).audioUrl()).startsWith("http");
		}
	}

	@Test
	void everyStarterShowsNewestEpisodeActuallyPlays() throws Exception {
		CuratedPodcastCatalog catalog = new CuratedPodcastCatalog(new DefaultResourceLoader(), properties, new ObjectMapper());
		var load = CuratedPodcastCatalog.class.getDeclaredMethod("load");
		load.setAccessible(true);
		load.invoke(catalog);
		PodcastAudioCheck audio = new PodcastAudioCheck(http, java.time.Clock.systemUTC());

		for (CuratedPodcastCatalog.CuratedPodcast starter : catalog.getPodcasts()) {
			Thread.sleep(1500); // Apple's directory rate-limits bursts
			var found = directory.findById(starter.appleId());
			if (found.isEmpty()) {
				System.out.println("[live-audio] LOOKUP-EMPTY (Apple limit or removed) " + starter.name());
				continue;
			}
			PodcastFacts show = found.get();
			PodcastAudioCheck.Verdict verdict = audio.forEpisodes(feeds.fetchFeed(show.feedUrl()).episodes());
			System.out.println("[live-audio] " + verdict + " " + starter.name());
			assertThat(verdict).as(starter.name()).isNotEqualTo(PodcastAudioCheck.Verdict.UNPLAYABLE);
		}
	}

	@Test
	void aNameSearchReturnsLookAlikesSoTheParentMustChoose() {
		List<PodcastFacts> shows = directory.search("Wow in the World");

		assertThat(shows).hasSizeGreaterThan(1);
		assertThat(shows.stream().map(PodcastFacts::collectionId)).contains(1233834541L);
	}

	@Test
	void theInternalAddressGuardStopsARealRequestBeforeItLeaves() {
		org.assertj.core.api.Assertions.assertThatThrownBy(
				() -> http.get(java.net.URI.create("http://127.0.0.1:8080/api/tags"), 1000))
				.isInstanceOf(com.aram.backend.exception.PodcastLookupException.class);
	}
}
