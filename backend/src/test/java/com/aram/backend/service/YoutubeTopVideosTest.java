package com.aram.backend.service;

import com.aram.backend.model.Video;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class YoutubeTopVideosTest {

	private static Video v(String id, Long views) {
		return Video.builder().videoId(id).title(id).description("long description").viewCount(views).build();
	}

	@Test
	void keepsTheMostViewedFirstAndDropsUnknownCounts() {
		List<Video> top = YoutubeVideoService.topByViews(List.of(v("a", 10L), v("b", 500L), v("c", null), v("d", 90L)), 2);

		assertThat(top).extracting(Video::getVideoId).containsExactly("b", "d");
		assertThat(top).allMatch(video -> "long description".equals(video.getDescription()));
	}

	@Test
	void shortsAreNotTopPicksButVideosOfUnknownLengthStay() {
		Video shortClip = v("s", 9_000L);
		shortClip.setDurationSeconds(45);
		Video full = v("f", 100L);
		full.setDurationSeconds(600);

		assertThat(YoutubeVideoService.topByViews(List.of(shortClip, full, v("u", 50L)), 5)).extracting(Video::getVideoId)
				.containsExactly("f", "u");
	}

	@Test
	void noCountsMeansNoTopVideos() {
		assertThat(YoutubeVideoService.topByViews(List.of(v("a", null)), 5)).isEmpty();
	}

	@Test
	void veryLongDescriptionsAreCutSoTheCacheDocumentStaysSmall() {
		Video video = Video.builder().videoId("a").viewCount(1L).description("x".repeat(5000)).build();

		assertThat(YoutubeVideoService.topByViews(List.of(video), 5).get(0).getDescription()).hasSize(500);
	}

	@Test
	void theWatchableListIsTheLatestVideosThenTheTopOnesNotAlreadyThere() {
		List<Video> merged = YoutubeVideoService.mergeWatchable(List.of(v("n1", 1L), v("n2", 2L)), List.of(v("n2", 2L), v("old-hit", 9_000L)));

		assertThat(merged).extracting(Video::getVideoId).containsExactly("n1", "n2", "old-hit");
		assertThat(YoutubeVideoService.mergeWatchable(List.of(v("a", 1L)), null)).extracting(Video::getVideoId).containsExactly("a");
	}
}
