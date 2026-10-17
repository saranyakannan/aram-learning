package com.aram.backend.podcast;

import com.aram.backend.catalog.ContentSource;
import com.aram.backend.catalog.EpisodeCacheRepository;
import com.aram.backend.catalog.InMemoryStores;
import com.aram.backend.catalog.Statuses;
import com.aram.backend.config.AramProperties;
import com.aram.backend.exception.PodcastLookupException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PodcastFeedServiceTest {

	private static final String FEED = "https://feeds.example.com/show.xml";
	private static final String XML = """
			<rss version="2.0"><channel><title>Show</title>
			<item><title>Fresh</title><enclosure url="https://cdn.example.com/1.mp3" type="audio/mpeg"/></item>
			</channel></rss>""";

	private final FakeHttp http = new FakeHttp();
	private final Map<String, EpisodeCacheRepository.Cached> stored = new HashMap<>();
	private Instant now = Instant.parse("2026-10-14T12:00:00Z");
	private PodcastFeedService service;
	private ContentSource show;

	@BeforeEach
	void setUp() {
		EpisodeCacheRepository cache = new EpisodeCacheRepository(null) {
			@Override
			public Optional<Cached> find(String sourceId) {
				return Optional.ofNullable(stored.get(sourceId));
			}

			@Override
			public void save(String sourceId, List<PodcastEpisode> episodes, Instant fetchedAt) {
				stored.put(sourceId, new Cached(episodes, fetchedAt));
			}
		};
		Clock clock = new Clock() {
			@Override
			public java.time.ZoneId getZone() {
				return ZoneOffset.UTC;
			}

			@Override
			public Clock withZone(java.time.ZoneId zone) {
				return this;
			}

			@Override
			public Instant instant() {
				return now;
			}
		};
		service = new PodcastFeedService(http, cache, new AramProperties(), clock);
		ContentSource base = InMemoryStores.source("UC" + "a".repeat(22), "Show", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 36, 119, "podcasts");
		show = new ContentSource("podcast_123", "Show", "show", ContentSource.PODCAST_FEED, ContentSource.APPLE_PODCASTS,
				"https://podcasts.apple.com/podcast/id123", "123", "d", List.of("podcasts"), List.of(), Statuses.Catalog.ACTIVE,
				Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, null, 36, 119, "Pub", null, null, base.createdAt(),
				base.updatedAt(), base.lastVerifiedAt(), FEED);
		http.answer(FEED, XML);
	}

	@Test
	void episodesAreFetchedOnceThenServedFromTheCacheUntilItExpires() {
		assertThat(service.episodesFor(show)).extracting(PodcastEpisode::title).containsExactly("Fresh");
		assertThat(service.episodesFor(show)).hasSize(1);
		assertThat(http.requested).hasSize(1);

		now = now.plusSeconds(60L * 60 * 7);
		service.episodesFor(show);

		assertThat(http.requested).hasSize(2);
	}

	@Test
	void whenAnExpiredFeedIsDownTheLastCopyIsServedInsteadOfAnError() {
		service.episodesFor(show);
		now = now.plusSeconds(60L * 60 * 7);
		http.answer(FEED, 503, "down");

		assertThat(service.episodesFor(show)).extracting(PodcastEpisode::title).containsExactly("Fresh");
	}

	@Test
	void withNoCachedCopyAFeedFailureIsAnError() {
		http.answer(FEED, 500, "boom");

		assertThatThrownBy(() -> service.episodesFor(show)).isInstanceOf(PodcastLookupException.class);
	}

	@Test
	void aSourceThatIsNotAPodcastHasNoEpisodesAndNothingIsFetched() {
		ContentSource channel = InMemoryStores.source("UC" + "b".repeat(22), "Channel", Statuses.Assessment.ASSESSED, 0, 99, "math");

		assertThat(service.episodesFor(channel)).isEmpty();
		assertThat(http.requested).isEmpty();
	}

	@Test
	void vettingReadsTheFeedLiveWithoutTouchingTheCache() {
		assertThat(service.fetchFeed(FEED).title()).isEqualTo("Show");
		assertThat(stored).isEmpty();
	}

	@Test
	void aShowWhoseAudioTheHostRefusesIsHiddenFromPlaybackButAGoodOneIsNot() {
		service = new PodcastFeedService(http, new EpisodeCacheRepository(null) {
			@Override
			public Optional<Cached> find(String sourceId) {
				return Optional.empty();
			}

			@Override
			public void save(String sourceId, List<PodcastEpisode> episodes, Instant fetchedAt) {
			}
		}, new AramProperties(), Clock.fixed(now, ZoneOffset.UTC), new PodcastAudioCheck(http, Clock.fixed(now, ZoneOffset.UTC)));
		http.answers.put("https://cdn.example.com/1.mp3", new HttpFetcher.Response(403, new byte[0], false, "application/xml"));

		assertThat(service.episodesFor(show)).isEmpty();

		http.answers.put("https://cdn.example.com/1.mp3", new HttpFetcher.Response(200, new byte[0], false, "audio/mpeg"));
		service = new PodcastFeedService(http, new EpisodeCacheRepository(null) {
			@Override
			public Optional<Cached> find(String sourceId) {
				return Optional.empty();
			}

			@Override
			public void save(String sourceId, List<PodcastEpisode> episodes, Instant fetchedAt) {
			}
		}, new AramProperties(), Clock.fixed(now, ZoneOffset.UTC), new PodcastAudioCheck(http, Clock.fixed(now, ZoneOffset.UTC)));

		assertThat(service.episodesFor(show)).extracting(PodcastEpisode::title).containsExactly("Fresh");
	}
}
