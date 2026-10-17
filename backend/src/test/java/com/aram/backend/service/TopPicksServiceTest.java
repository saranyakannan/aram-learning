package com.aram.backend.service;

import com.aram.backend.catalog.ContentSource;
import com.aram.backend.model.Channel;
import com.aram.backend.model.TopPick;
import com.aram.backend.model.Video;
import com.aram.backend.podcast.PodcastEpisode;
import com.aram.backend.podcast.PodcastFeedService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;

import static com.aram.backend.catalog.InMemoryStores.source;
import static com.aram.backend.catalog.InMemoryStores.youtubeId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TopPicksServiceTest {

	private static TopPick video(String channel, int n, long views) {
		return new TopPick(TopPick.VIDEO, channel + "-v" + n, channel, channel, "Video " + n, null, null, views, null, null);
	}

	private static TopPick episode(String show) {
		return new TopPick(TopPick.EPISODE, show + "-e", show, show, "Episode of " + show, null, null, null, "https://a/b.mp3", 600);
	}

	private static List<TopPick> videos(int channels, int perChannel) {
		List<TopPick> all = new ArrayList<>();
		for (int c = 0; c < channels; c++) {
			for (int n = 0; n < perChannel; n++) {
				all.add(video("ch" + c, n, 1_000_000L - c * 1000L - n));
			}
		}
		return all;
	}

	@Test
	void theStripHasTenItemsMixingVideosAndEpisodesWithNoMoreThanTwoVideosPerChannel() {
		List<TopPick> strip = TopPicksService.select(videos(8, 5), List.of(episode("s1"), episode("s2"), episode("s3"), episode("s4")), 10, new Random(1));

		assertThat(strip).hasSize(10);
		assertThat(strip).extracting(TopPick::type).contains(TopPick.VIDEO, TopPick.EPISODE);
		assertThat(strip.stream().filter(p -> p.type().equals(TopPick.EPISODE)).count()).isBetween(2L, 4L);
		for (int c = 0; c < 8; c++) {
			String channel = "ch" + c;
			assertThat(strip.stream().filter(p -> p.type().equals(TopPick.VIDEO) && p.sourceId().equals(channel)).count()).isLessThanOrEqualTo(2);
		}
		assertThat(strip).extracting(TopPick::id).doesNotHaveDuplicates();
	}

	@Test
	void videosComeFromTheMostViewedAndTheStripChangesBetweenVisits() {
		List<TopPick> pool = new ArrayList<>(videos(12, 2));
		Set<List<String>> seen = new HashSet<>();
		for (long seed = 0; seed < 20; seed++) {
			List<TopPick> strip = TopPicksService.select(pool, List.of(), 10, new Random(seed));
			seen.add(strip.stream().map(TopPick::id).toList());
			assertThat(strip).allMatch(p -> p.viewCount() >= 1_000_000L - 12_000L, "only the most viewed half-and-a-bit of the pool");
		}
		assertThat(seen.size()).as("different visits give different strips").isGreaterThan(5);
	}

	@Test
	void theMostViewedVideoIsNotAlwaysFirst() {
		List<TopPick> pool = videos(12, 2);
		String mostViewed = pool.get(0).id();
		long first = IntStream.range(0, 40).filter(seed -> TopPicksService.select(pool, List.of(), 10, new Random(seed)).get(0).id().equals(mostViewed)).count();

		assertThat(first).isLessThan(20);
	}

	@Test
	void neighboursComeFromDifferentChannelsWheneverTheStripAllowsIt() {
		for (long seed = 0; seed < 30; seed++) {
			List<TopPick> strip = TopPicksService.select(videos(6, 4), List.of(episode("s1"), episode("s2")), 10, new Random(seed));
			for (int i = 1; i < strip.size(); i++) {
				assertThat(strip.get(i).sourceId()).as("seed %d position %d", seed, i).isNotEqualTo(strip.get(i - 1).sourceId());
			}
		}
	}

	@Test
	void withoutPodcastsTheStripIsAllVideosAndWithoutVideosAllEpisodes() {
		assertThat(TopPicksService.select(videos(8, 3), List.of(), 10, new Random(2))).hasSize(10).allMatch(p -> p.type().equals(TopPick.VIDEO));
		assertThat(TopPicksService.select(List.of(), List.of(episode("a"), episode("b"), episode("c")), 10, new Random(2)))
				.hasSize(3).allMatch(p -> p.type().equals(TopPick.EPISODE));
		assertThat(TopPicksService.select(List.of(), List.of(), 10, new Random(2))).isEmpty();
	}

	@Test
	void aSmallCatalogGivesWhatItHasWithoutRepeats() {
		List<TopPick> strip = TopPicksService.select(videos(2, 5), List.of(episode("s1")), 10, new Random(3));

		assertThat(strip).hasSize(5);
		assertThat(strip).extracting(TopPick::id).doesNotHaveDuplicates();
	}

	@Test
	void onlyApprovedSourcesFeedTheStripAndABrokenChannelDoesNotBlankIt() {
		ParentChannelService parentChannels = mock(ParentChannelService.class);
		YoutubeVideoService youtube = mock(YoutubeVideoService.class);
		PodcastFeedService podcasts = mock(PodcastFeedService.class);
		Channel good = channel("a");
		Channel broken = channel("b");
		when(parentChannels.approvedChannels("p")).thenReturn(List.of(good, broken));
		when(parentChannels.approvedPodcasts("p")).thenReturn(List.of());
		when(youtube.getTopVideos(good)).thenReturn(List.of(Video.builder().videoId("v1").title("T").channelId(good.id()).viewCount(5L).build()));
		when(youtube.getTopVideos(broken)).thenThrow(new com.aram.backend.exception.YoutubeIntegrationException("boom", null));

		List<TopPick> strip = new TopPicksService(parentChannels, youtube, podcasts).topPicks("p", 10, new Random(1));

		assertThat(strip).extracting(TopPick::id).containsExactly("v1");
		verify(youtube, never()).getTopVideos(channel("not-approved"));
		verify(podcasts, never()).episodesFor(any(ContentSource.class));
	}

	@Test
	void aLargerSizeGivesMoreTilesAndScalesTheEpisodeShare() {
		List<TopPick> big = videos(20, 2);
		List<TopPick> shows = IntStream.range(0, 12).mapToObj(n -> episode("s" + n)).toList();

		List<TopPick> strip = TopPicksService.select(big, shows, 30, new Random(4));

		assertThat(strip).hasSize(30);
		long episodes = strip.stream().filter(p -> p.type().equals(TopPick.EPISODE)).count();
		assertThat(episodes).isBetween(8L, 10L);
		assertThat(strip).extracting(TopPick::id).doesNotHaveDuplicates();
	}

	@Test
	void theRequestedSizeIsClampedToAWorkableRange() {
		assertThat(TopPicksService.clamp(0)).isEqualTo(1);
		assertThat(TopPicksService.clamp(-5)).isEqualTo(1);
		assertThat(TopPicksService.clamp(10)).isEqualTo(10);
		assertThat(TopPicksService.clamp(500)).isEqualTo(TopPicksService.MAX_SIZE);
	}

	@Test
	void aVideosLengthTravelsWithItsTile() {
		ParentChannelService parentChannels = mock(ParentChannelService.class);
		YoutubeVideoService youtube = mock(YoutubeVideoService.class);
		Channel ch = channel("a");
		when(parentChannels.approvedChannels("p")).thenReturn(List.of(ch));
		when(parentChannels.approvedPodcasts("p")).thenReturn(List.of());
		when(youtube.getTopVideos(ch)).thenReturn(List.of(Video.builder().videoId("v1").channelId(ch.id()).viewCount(5L).durationSeconds(754).build()));

		List<TopPick> strip = new TopPicksService(parentChannels, youtube, mock(PodcastFeedService.class)).topPicks("p", 10, new Random(1));

		assertThat(strip.get(0).durationSeconds()).isEqualTo(754);
	}

	private static Channel channel(String letter) {
		var src = source(youtubeId(letter.charAt(0)), "Channel " + letter, com.aram.backend.catalog.Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 24, 71, "math");
		return new Channel(src.id(), src.name(), null, null, src.platformSourceId(), null, 2, 6, List.of("math"), null, Channel.YOUTUBE_CHANNEL);
	}
}
