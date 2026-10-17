package com.aram.backend.controller;

import com.aram.backend.auth.AuthFilter;
import com.aram.backend.auth.AuthenticatedParent;
import com.aram.backend.catalog.ContentSource;
import com.aram.backend.catalog.Statuses;
import com.aram.backend.catalog.TestWorld;
import com.aram.backend.exception.GlobalExceptionHandler;
import com.aram.backend.model.Video;
import com.aram.backend.podcast.PodcastEpisode;
import com.aram.backend.podcast.PodcastFeedService;
import com.aram.backend.service.ParentChannelService;
import com.aram.backend.service.YoutubeVideoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static com.aram.backend.catalog.InMemoryStores.source;
import static com.aram.backend.catalog.InMemoryStores.youtubeId;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Playback is authorized by the server from the parent's approvals; the browser's request cannot widen it. */
class PlaybackAuthorizationTest {

	private static final AuthenticatedParent PARENT = new AuthenticatedParent(TestWorld.PARENT, "Asha", "a@example.com");

	private TestWorld world;
	private YoutubeVideoService youtube;
	private MockMvc mvc;
	private ContentSource approved;
	private ContentSource suggestedOnly;
	private ContentSource approvedShow;
	private ContentSource suggestedShow;
	private PodcastFeedService feeds;

	@BeforeEach
	void setUp() {
		world = new TestWorld();
		String childId = world.onboard(TestWorld.PARENT, 55).childId();
		approved = world.addSource(source(youtubeId('a'), "Approved", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 24, 71, "math"));
		suggestedOnly = world.addSource(source(youtubeId('b'), "Suggested only", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 24, 71, "math"));
		approvedShow = world.addSource(show("1111111", "Approved show"));
		suggestedShow = world.addSource(show("2222222", "Suggested show"));
		world.childCatalog.approve(TestWorld.PARENT, childId, 55, List.of(approved.id(), approvedShow.id()));
		feeds = mock(PodcastFeedService.class);
		when(feeds.episodesFor(any())).thenAnswer(call -> List.of(
				new PodcastEpisode("e1", "An episode", "d", null, "https://cdn.example.com/1.mp3", "audio/mpeg", 60, null)));

		youtube = mock(YoutubeVideoService.class);
		when(youtube.getLatestVideos(any())).thenAnswer(call -> List.of(Video.builder().videoId("v1").title("A video").build()));
		when(youtube.getWatchableVideos(any())).thenAnswer(call -> List.of(Video.builder().videoId("v1").title("A video").build()));
		ParentChannelService channels = new ParentChannelService(world.catalog, world.childCatalog);
		mvc = MockMvcBuilders.standaloneSetup(new VideoController(channels, youtube), new ChannelController(channels),
						new PodcastController(channels, feeds))
				.setControllerAdvice(new GlobalExceptionHandler()).build();
	}

	private ContentSource show(String appleId, String name) {
		return new ContentSource("podcast_" + appleId, name, name.toLowerCase(), ContentSource.PODCAST_FEED,
				ContentSource.APPLE_PODCASTS, "https://podcasts.apple.com/podcast/id" + appleId, appleId, "d", List.of("math"),
				List.of(ContentSource.PREPOPULATED), Statuses.Catalog.ACTIVE, Statuses.Assessment.APPROVED_FOR_RECOMMENDATION,
				null, 24, 119, "Pub", null, null, java.time.Instant.EPOCH, java.time.Instant.EPOCH, java.time.Instant.EPOCH,
				"https://feeds.example.com/" + appleId + ".xml");
	}

	@Test
	void anApprovedShowsEpisodesAreServedAndASuggestedShowsAreNotFound() throws Exception {
		mvc.perform(get("/api/podcasts/" + approvedShow.id() + "/episodes").accept(MediaType.APPLICATION_JSON)
						.requestAttr(AuthFilter.ATTRIBUTE, PARENT))
				.andExpect(status().isOk()).andExpect(jsonPath("$[0].title").value("An episode"))
				.andExpect(jsonPath("$[0].audioUrl").value("https://cdn.example.com/1.mp3"));
		mvc.perform(get("/api/podcasts/" + suggestedShow.id() + "/episodes").accept(MediaType.APPLICATION_JSON)
						.requestAttr(AuthFilter.ATTRIBUTE, PARENT))
				.andExpect(status().isNotFound());
		mvc.perform(get("/api/podcasts/" + approved.id() + "/episodes").accept(MediaType.APPLICATION_JSON)
						.requestAttr(AuthFilter.ATTRIBUTE, PARENT))
				.andExpect(status().isNotFound());
	}

	@Test
	void thePodcastFeedOnlyContainsApprovedShowsAndTheVideoFeedNeverContainsAnyShow() throws Exception {
		mvc.perform(get("/api/podcast-feed").accept(MediaType.APPLICATION_JSON).requestAttr(AuthFilter.ATTRIBUTE, PARENT))
				.andExpect(jsonPath("$['" + approvedShow.id() + "']").isArray())
				.andExpect(jsonPath("$['" + suggestedShow.id() + "']").doesNotExist());
		mvc.perform(get("/api/feed").param("channelIds", approvedShow.id(), approved.id())
						.accept(MediaType.APPLICATION_JSON).requestAttr(AuthFilter.ATTRIBUTE, PARENT))
				.andExpect(jsonPath("$['" + approvedShow.id() + "']").doesNotExist())
				.andExpect(jsonPath("$['" + approved.id() + "']").isArray());
	}

	@Test
	void aShowIsNeverPlayedThroughTheYoutubeVideosEndpointAndAnotherParentGetsNoEpisodes() throws Exception {
		mvc.perform(get("/api/channels/" + approvedShow.id() + "/videos").accept(MediaType.APPLICATION_JSON)
						.requestAttr(AuthFilter.ATTRIBUTE, PARENT))
				.andExpect(status().isNotFound());
		verify(youtube, never()).getLatestVideos(any());
		verify(youtube, never()).getWatchableVideos(any());

		world.onboard(TestWorld.OTHER_PARENT, 55);
		AuthenticatedParent other = new AuthenticatedParent(TestWorld.OTHER_PARENT, "Other", "o@example.com");
		mvc.perform(get("/api/podcasts/" + approvedShow.id() + "/episodes").accept(MediaType.APPLICATION_JSON)
						.requestAttr(AuthFilter.ATTRIBUTE, other))
				.andExpect(status().isNotFound());
	}

	@Test
	void anApprovedChannelsVideosAreServed() throws Exception {
		mvc.perform(get("/api/channels/" + approved.id() + "/videos").accept(MediaType.APPLICATION_JSON).requestAttr(AuthFilter.ATTRIBUTE, PARENT))
				.andExpect(status().isOk()).andExpect(jsonPath("$[0].title").value("A video"));
	}

	@Test
	void anyVideoOfAnApprovedChannelCanBeOpenedButAnUnknownOrForeignOneIsNotFound() throws Exception {
		when(youtube.getVideo(any(), org.mockito.ArgumentMatchers.eq("OLDvideo123"))).thenReturn(java.util.Optional.of(
				Video.builder().videoId("OLDvideo123").title("An older upload").build()));
		when(youtube.getVideo(any(), org.mockito.ArgumentMatchers.eq("OTHERchan01"))).thenReturn(java.util.Optional.empty());

		mvc.perform(get("/api/channels/" + approved.id() + "/videos/OLDvideo123").accept(MediaType.APPLICATION_JSON)
						.requestAttr(AuthFilter.ATTRIBUTE, PARENT))
				.andExpect(status().isOk()).andExpect(jsonPath("$.title").value("An older upload"));
		mvc.perform(get("/api/channels/" + approved.id() + "/videos/OTHERchan01").accept(MediaType.APPLICATION_JSON)
						.requestAttr(AuthFilter.ATTRIBUTE, PARENT))
				.andExpect(status().isNotFound());
	}

	@Test
	void aSingleVideoIsNeverServedForAChannelThatIsNotApprovedOrIsAShow() throws Exception {
		mvc.perform(get("/api/channels/" + suggestedOnly.id() + "/videos/OLDvideo123").accept(MediaType.APPLICATION_JSON)
						.requestAttr(AuthFilter.ATTRIBUTE, PARENT))
				.andExpect(status().isNotFound());
		mvc.perform(get("/api/channels/" + approvedShow.id() + "/videos/OLDvideo123").accept(MediaType.APPLICATION_JSON)
						.requestAttr(AuthFilter.ATTRIBUTE, PARENT))
				.andExpect(status().isNotFound());
		verify(youtube, never()).getVideo(any(), any());
	}

	@Test
	void aChannelThatIsOnlySuggestedIsNotFoundForPlaybackEvenThoughItIsVisibleToChooseFrom() throws Exception {
		mvc.perform(get("/api/channels/" + suggestedOnly.id() + "/videos").accept(MediaType.APPLICATION_JSON).requestAttr(AuthFilter.ATTRIBUTE, PARENT))
				.andExpect(status().isNotFound());
		mvc.perform(get("/api/channels/" + suggestedOnly.id()).accept(MediaType.APPLICATION_JSON).requestAttr(AuthFilter.ATTRIBUTE, PARENT))
				.andExpect(status().isOk());
		verify(youtube, never()).getLatestVideos(any());
		verify(youtube, never()).getWatchableVideos(any());
	}

	@Test
	void theFeedOnlyContainsApprovedChannelsWhateverTheRequestAsksFor() throws Exception {
		mvc.perform(get("/api/feed").param("channelIds", approved.id(), suggestedOnly.id(), "youtube_UCunknown000000000000000")
						.accept(MediaType.APPLICATION_JSON).requestAttr(AuthFilter.ATTRIBUTE, PARENT))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$['" + approved.id() + "']").isArray())
				.andExpect(jsonPath("$['" + suggestedOnly.id() + "']").doesNotExist());
		mvc.perform(get("/api/feed").accept(MediaType.APPLICATION_JSON).requestAttr(AuthFilter.ATTRIBUTE, PARENT))
				.andExpect(jsonPath("$['" + suggestedOnly.id() + "']").doesNotExist());
	}

	@Test
	void anotherParentsApprovalsGiveYouNothing() throws Exception {
		world.onboard(TestWorld.OTHER_PARENT, 55);
		AuthenticatedParent other = new AuthenticatedParent(TestWorld.OTHER_PARENT, "Other", "o@example.com");

		mvc.perform(get("/api/channels/" + approved.id() + "/videos").accept(MediaType.APPLICATION_JSON).requestAttr(AuthFilter.ATTRIBUTE, other))
				.andExpect(status().isNotFound());
		mvc.perform(get("/api/feed").accept(MediaType.APPLICATION_JSON).requestAttr(AuthFilter.ATTRIBUTE, other)).andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void aChannelTheCatalogNoLongerClearsStopsPlayingImmediately() throws Exception {
		world.catalogRepo.sources.put(approved.id(), new ContentSource(approved.id(), approved.name(), approved.normalizedName(),
				approved.sourceType(), approved.platform(), approved.canonicalUrl(), approved.platformSourceId(),
				approved.description(), approved.tagIds(), approved.originTypes(), Statuses.Catalog.ACTIVE,
				Statuses.Assessment.REJECTED, approved.latestAssessmentId(), approved.minAgeMonths(), approved.maxAgeMonths(),
				approved.handle(), approved.thumbnailUrl(), approved.subscriberCount(), approved.createdAt(),
				approved.updatedAt(), approved.lastVerifiedAt()));

		mvc.perform(get("/api/channels/" + approved.id() + "/videos").accept(MediaType.APPLICATION_JSON).requestAttr(AuthFilter.ATTRIBUTE, PARENT))
				.andExpect(status().isNotFound());
	}
}
