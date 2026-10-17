package com.aram.backend.service;

import com.google.api.client.util.DateTime;
import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.model.VideoContentDetails;
import com.google.api.services.youtube.model.VideoListResponse;
import com.google.api.services.youtube.model.VideoSnippet;
import com.google.api.services.youtube.model.VideoStatistics;
import com.aram.backend.config.AramProperties;
import com.aram.backend.model.CachedChannelVideos;
import com.aram.backend.model.Channel;
import com.aram.backend.model.Video;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import java.io.IOException;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Any video of an approved channel can be opened, but only a video that really belongs to that channel. */
class YoutubeSingleVideoTest {

	private static final String OWN_ID = "UCownChannel0000000000aa";
	private static final Channel CHANNEL = new Channel("youtube_" + OWN_ID, "Own channel", "Stories", "d", OWN_ID, null, 2, 6,
			List.of("math"), null, Channel.YOUTUBE_CHANNEL);

	private YouTube youTube;
	private VideoCacheRepository cache;
	private YoutubeVideoService service;
	private final AtomicInteger youtubeCalls = new AtomicInteger();
	private VideoListResponse answer;

	@BeforeEach
	void setUp() throws IOException {
		youTube = mock(YouTube.class, Answers.RETURNS_DEEP_STUBS);
		cache = mock(VideoCacheRepository.class);
		when(cache.findFresh(any())).thenReturn(Optional.empty());
		service = new YoutubeVideoService(youTube, new AramProperties(), cache);
		when(youTube.videos().list(any()).setId(any()).execute()).thenAnswer(call -> {
			youtubeCalls.incrementAndGet();
			return answer;
		});
	}

	private static com.google.api.services.youtube.model.Video youtubeVideo(String id, String channelId) {
		return new com.google.api.services.youtube.model.Video().setId(id)
				.setSnippet(new VideoSnippet().setChannelId(channelId).setTitle("Title " + id).setDescription("About " + id)
						.setPublishedAt(new DateTime("2025-01-02T03:04:05Z")))
				.setContentDetails(new VideoContentDetails().setDuration("PT12M34S"))
				.setStatistics(new VideoStatistics().setViewCount(BigInteger.valueOf(1234)));
	}

	private void youtubeReturns(com.google.api.services.youtube.model.Video video) {
		answer = new VideoListResponse().setItems(List.of(video));
	}

	@Test
	void anOlderVideoOfTheChannelIsLookedUpWithItsDetails() {
		youtubeReturns(youtubeVideo("OLDvideo123", OWN_ID));

		Video video = service.getVideo(CHANNEL, "OLDvideo123").orElseThrow();

		assertThat(video.getTitle()).isEqualTo("Title OLDvideo123");
		assertThat(video.getDurationSeconds()).isEqualTo(754);
		assertThat(video.getViewCount()).isEqualTo(1234L);
		assertThat(video.getChannelId()).isEqualTo(CHANNEL.id());
		assertThat(video.getVideoUrl()).endsWith("OLDvideo123");
	}

	@Test
	void aVideoOfAnotherChannelIsRefusedEvenThoughItExists() {
		youtubeReturns(youtubeVideo("OTHERchan01", "UCsomeoneElse000000000000"));

		assertThat(service.getVideo(CHANNEL, "OTHERchan01")).isEmpty();
	}

	@Test
	void anUnknownVideoIsRefused() {
		answer = new VideoListResponse().setItems(List.of());

		assertThat(service.getVideo(CHANNEL, "gone1234567")).isEmpty();
	}

	@Test
	void aMalformedIdNeverReachesYoutube() {
		assertThat(service.getVideo(CHANNEL, "../../etc/passwd")).isEmpty();
		assertThat(service.getVideo(CHANNEL, "short")).isEmpty();
		assertThat(service.getVideo(CHANNEL, null)).isEmpty();
		assertThat(youtubeCalls.get()).isZero();
	}

	@Test
	void aChannelWithoutAYoutubeIdCannotVouchForAnyVideo() {
		Channel handleOnly = new Channel("youtube_x", "X", null, null, null, "@x", 2, 6, List.of(), null, Channel.YOUTUBE_CHANNEL);

		assertThat(service.getVideo(handleOnly, "OLDvideo123")).isEmpty();
		assertThat(youtubeCalls.get()).isZero();
	}

	@Test
	void aVideoAlreadyInTheCachedListsCostsNoYoutubeCall() {
		Video cached = Video.builder().videoId("CACHEDvideo").title("Cached").channelId(CHANNEL.id()).build();
		when(cache.findFresh(CHANNEL.id())).thenReturn(Optional.of(new CachedChannelVideos(CHANNEL.id(), List.of(cached), 1L, List.of())));

		assertThat(service.getVideo(CHANNEL, "CACHEDvideo")).get().extracting(Video::getTitle).isEqualTo("Cached");
		assertThat(youtubeCalls.get()).isZero();
	}

	@Test
	void aLookedUpVideoIsRememberedSoOpeningItAgainCostsNothing() {
		youtubeReturns(youtubeVideo("OLDvideo123", OWN_ID));

		service.getVideo(CHANNEL, "OLDvideo123");
		service.getVideo(CHANNEL, "OLDvideo123");
		service.getVideo(CHANNEL, "OLDvideo123");

		assertThat(youtubeCalls.get()).isEqualTo(1);
	}
}
