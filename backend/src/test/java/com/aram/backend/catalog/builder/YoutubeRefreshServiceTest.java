package com.aram.backend.catalog.builder;

import com.aram.backend.agent.YoutubeChannelLookup;
import com.aram.backend.catalog.ContentSource;
import com.aram.backend.catalog.EligibilityPolicy;
import com.aram.backend.catalog.InMemoryStores;
import com.aram.backend.catalog.Statuses;
import com.aram.backend.exception.YoutubeIntegrationException;
import com.aram.backend.model.ChannelFacts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class YoutubeRefreshServiceTest {

	private static final Instant NOW = Instant.parse("2026-11-01T02:00:00Z");
	private static final Instant LONG_AGO = Instant.parse("2026-09-01T00:00:00Z");

	private final InMemoryStores.Catalog repo = new InMemoryStores.Catalog();
	private final YoutubeChannelLookup lookup = mock(YoutubeChannelLookup.class);
	private final List<Integer> batchSizes = new ArrayList<>();
	private final List<Long> pauses = new ArrayList<>();
	private YoutubeRefreshService service;

	@BeforeEach
	void setUp() {
		service = new YoutubeRefreshService(repo, lookup, Clock.fixed(NOW, ZoneOffset.UTC), pauses::add);
	}

	private static String channelId(int n) {
		return "UC" + String.format("%022d", n);
	}

	private ContentSource youtube(int n, String origin, Instant verified) {
		ContentSource base = InMemoryStores.source(channelId(n), "Old name " + n, Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 36, 71, "science");
		return new ContentSource(base.id(), base.name(), base.normalizedName(), base.sourceType(), base.platform(), base.canonicalUrl(),
				base.platformSourceId(), "Old description", base.tagIds(), List.of(origin), base.catalogStatus(), base.assessmentStatus(),
				null, 36, 71, "@old", "https://old/thumb.jpg", 1L, LONG_AGO, LONG_AGO, verified);
	}

	private static ChannelFacts facts(int n) {
		return new ChannelFacts(channelId(n), "New title " + n, "@new" + n, "New description", "https://new/thumb" + n + ".jpg", 500L + n, true);
	}

	private void youtubeKnows(int... present) {
		when(lookup.findByIds(anyList())).thenAnswer(call -> {
			List<String> ids = call.getArgument(0);
			batchSizes.add(ids.size());
			Map<String, ChannelFacts> found = new LinkedHashMap<>();
			for (int n : present) {
				if (ids.contains(channelId(n))) {
					found.put(channelId(n), facts(n));
				}
			}
			return found;
		});
	}

	@Test
	void platformFieldsAndTheVerificationDateAreRefreshedWhileCuratedTextIsKept() {
		repo.sources.put(youtube(1, ContentSource.PREPOPULATED, LONG_AGO).id(), youtube(1, ContentSource.PREPOPULATED, LONG_AGO));
		youtubeKnows(1);

		YoutubeRefreshService.Result result = service.run(false);

		ContentSource refreshed = repo.sources.get("youtube_" + channelId(1));
		assertThat(result.refreshed()).isEqualTo(1);
		assertThat(refreshed.name()).as("curated name is ours, not API data").isEqualTo("Old name 1");
		assertThat(refreshed.description()).isEqualTo("Old description");
		assertThat(refreshed.handle()).isEqualTo("@new1");
		assertThat(refreshed.thumbnailUrl()).isEqualTo("https://new/thumb1.jpg");
		assertThat(refreshed.subscriberCount()).isEqualTo(501L);
		assertThat(refreshed.canonicalUrl()).isEqualTo("https://www.youtube.com/@new1");
		assertThat(refreshed.lastVerifiedAt()).isEqualTo(NOW);
		assertThat(refreshed.isStaleYoutubeData(NOW)).isFalse();
		assertThat(refreshed.catalogStatus()).isEqualTo(Statuses.Catalog.ACTIVE);
	}

	@Test
	void aSubmittedChannelsTitleAndDescriptionAreApiDataSoTheyAreRefreshedToo() {
		repo.sources.put("youtube_" + channelId(2), youtube(2, ContentSource.PARENT_SUBMITTED, LONG_AGO));
		youtubeKnows(2);

		service.run(false);

		assertThat(repo.sources.get("youtube_" + channelId(2)).name()).isEqualTo("New title 2");
		assertThat(repo.sources.get("youtube_" + channelId(2)).description()).isEqualTo("New description");
		assertThat(repo.sources.get("youtube_" + channelId(2)).normalizedName()).isEqualTo("new title 2");
	}

	@Test
	void aChannelYouTubeNoLongerReturnsLeavesTheLiveCatalogAndLosesItsPlatformData() {
		repo.sources.put("youtube_" + channelId(3), youtube(3, ContentSource.PARENT_SUBMITTED, LONG_AGO));
		repo.sources.put("youtube_" + channelId(4), youtube(4, ContentSource.PREPOPULATED, LONG_AGO));
		youtubeKnows();

		YoutubeRefreshService.Result result = service.run(false);

		assertThat(result.vanished()).isEqualTo(2);
		assertThat(result.vanishedIds()).containsExactlyInAnyOrder("youtube_" + channelId(3), "youtube_" + channelId(4));
		ContentSource submitted = repo.sources.get("youtube_" + channelId(3));
		assertThat(submitted.catalogStatus()).isEqualTo(Statuses.Catalog.UNDER_REVIEW);
		assertThat(submitted.thumbnailUrl()).isNull();
		assertThat(submitted.subscriberCount()).isNull();
		assertThat(submitted.handle()).isNull();
		assertThat(submitted.name()).isEqualTo("Unavailable channel");
		assertThat(submitted.description()).isEmpty();
		assertThat(repo.sources.get("youtube_" + channelId(4)).name()).as("curated text stays").isEqualTo("Old name 4");
		assertThat(new EligibilityPolicy().stillPlayable(submitted)).as("no longer playable").isFalse();
		assertThat(new EligibilityPolicy().forChild(submitted, 50).eligible()).isFalse();
	}

	@Test
	void aFailedLookupIsRetriedAndNeverMistakenForAVanishedChannel() {
		repo.sources.put("youtube_" + channelId(5), youtube(5, ContentSource.PREPOPULATED, LONG_AGO));
		when(lookup.findByIds(anyList())).thenThrow(new YoutubeIntegrationException("quota", null));

		YoutubeRefreshService.Result result = service.run(false);

		assertThat(result.failed()).isEqualTo(1);
		assertThat(result.vanished()).isZero();
		assertThat(pauses).as("backoff between the 3 attempts").containsExactly(2000L, 4000L);
		assertThat(repo.sources.get("youtube_" + channelId(5)).catalogStatus()).isEqualTo(Statuses.Catalog.ACTIVE);
		assertThat(repo.sources.get("youtube_" + channelId(5)).lastVerifiedAt()).isEqualTo(LONG_AGO);
	}

	@Test
	void aTransientFailureThatClearsOnRetryStillRefreshes() {
		repo.sources.put("youtube_" + channelId(6), youtube(6, ContentSource.PREPOPULATED, LONG_AGO));
		AtomicInteger calls = new AtomicInteger();
		when(lookup.findByIds(anyList())).thenAnswer(call -> {
			if (calls.getAndIncrement() == 0) {
				throw new YoutubeIntegrationException("blip", null);
			}
			return Map.of(channelId(6), facts(6));
		});

		YoutubeRefreshService.Result result = service.run(false);

		assertThat(result.refreshed()).isEqualTo(1);
		assertThat(result.failed()).isZero();
	}

	@Test
	void channelsAreRefreshedFiftyAtATimeAtOneQuotaUnitPerBatch() {
		for (int n = 1; n <= 120; n++) {
			repo.sources.put("youtube_" + channelId(n), youtube(n, ContentSource.PREPOPULATED, LONG_AGO));
		}
		youtubeKnows(java.util.stream.IntStream.rangeClosed(1, 120).toArray());

		YoutubeRefreshService.Result result = service.run(false);

		assertThat(batchSizes).containsExactly(50, 50, 20);
		assertThat(result.quotaUnits()).isEqualTo(3);
		assertThat(result.refreshed()).isEqualTo(120);
	}

	@Test
	void aDryRunReadsYouTubeAndReportsButWritesNothing() {
		repo.sources.put("youtube_" + channelId(7), youtube(7, ContentSource.PARENT_SUBMITTED, LONG_AGO));
		repo.sources.put("youtube_" + channelId(8), youtube(8, ContentSource.PARENT_SUBMITTED, LONG_AGO));
		youtubeKnows(7);

		YoutubeRefreshService.Result result = service.run(true);

		assertThat(result.refreshed()).isEqualTo(1);
		assertThat(result.vanished()).isEqualTo(1);
		assertThat(repo.sources.get("youtube_" + channelId(7)).name()).isEqualTo("Old name 7");
		assertThat(repo.sources.get("youtube_" + channelId(8)).catalogStatus()).isEqualTo(Statuses.Catalog.ACTIVE);
	}

	@Test
	void podcastsAndNonYoutubeSourcesAreNeverTouched() {
		ContentSource podcast = new ContentSource("podcast_123", "Show", "show", ContentSource.PODCAST_FEED, ContentSource.APPLE_PODCASTS,
				"https://podcasts.apple.com/podcast/id123", "123", "d", List.of("podcasts"), List.of(ContentSource.PREPOPULATED),
				Statuses.Catalog.ACTIVE, Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, null, 36, 119, "Pub", null, null, LONG_AGO,
				LONG_AGO, LONG_AGO, "https://feeds.example.com/x.xml");
		repo.sources.put(podcast.id(), podcast);
		youtubeKnows();

		YoutubeRefreshService.Result result = service.run(false);

		assertThat(result.checked()).isZero();
		assertThat(repo.sources.get("podcast_123")).isEqualTo(podcast);
	}

	@Test
	void youtubeDataOlderThanThirtyDaysIsStaleAndItsThumbnailIsNotServed() {
		ContentSource stale = youtube(9, ContentSource.PREPOPULATED, NOW.minusSeconds(31L * 24 * 3600));
		ContentSource fresh = youtube(10, ContentSource.PREPOPULATED, NOW.minusSeconds(29L * 24 * 3600));

		assertThat(stale.isStaleYoutubeData(NOW)).isTrue();
		assertThat(fresh.isStaleYoutubeData(NOW)).isFalse();
		assertThat(youtube(11, ContentSource.PREPOPULATED, null).isStaleYoutubeData(NOW)).as("never verified counts as stale").isTrue();
	}
}
