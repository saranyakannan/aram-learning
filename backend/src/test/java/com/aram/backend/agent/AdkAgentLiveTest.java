package com.aram.backend.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import com.aram.backend.catalog.CatalogService;
import com.aram.backend.config.AramProperties;
import com.aram.backend.model.ChannelFacts;
import com.aram.backend.model.ParentProfile;
import com.aram.backend.model.Tag;
import com.aram.backend.podcast.PodcastDirectory;
import com.aram.backend.podcast.PodcastFeedService;
import com.aram.backend.podcast.PodcastLinkParser;
import com.aram.backend.podcast.SafeHttp;
import com.aram.backend.service.ParentProfileService;
import com.aram.backend.service.TagCatalogService;
import com.aram.backend.service.YoutubeVideoService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Real Gemini on Vertex AI and the real Apple directory, with the family data mocked: the Stories Podcast search on both
 * engines. Run with {@code ARAM_LIVE_AGENT=true ARAM_LIVE_PROJECT=<project> ./gradlew cleanTest test --tests '*AdkAgentLiveTest' --info}.
 */
@EnabledIfEnvironmentVariable(named = "ARAM_LIVE_AGENT", matches = "true")
class AdkAgentLiveTest {

	private static final String PARENT = "11111111-2222-3333-4444-555555555555";

	private AgentService build(boolean adk) throws Exception {
		String project = System.getenv("ARAM_LIVE_PROJECT");
		assertThat(project).as("ARAM_LIVE_PROJECT").isNotBlank();
		AramProperties properties = new AramProperties();
		Client client = Client.builder().vertexAI(true).project(project).location(properties.getGemini().getLocation())
				.credentials(GoogleCredentials.getApplicationDefault().createWithQuotaProject(project))
				.httpOptions(HttpOptions.builder().timeout(properties.getGemini().getRequestTimeoutMillis())
						.retryOptions(HttpRetryOptions.builder().attempts(1).build()).build())
				.build();
		GeminiClient gemini = new GeminiClient(() -> client, properties);

		ObjectMapper mapper = new ObjectMapper();
		Tag[] tags;
		try (InputStream in = getClass().getResourceAsStream("/tags.json")) {
			tags = mapper.readValue(in, Tag[].class);
		}
		TagCatalogService tagCatalog = mock(TagCatalogService.class);
		when(tagCatalog.getTags()).thenReturn(List.of(tags));
		ChannelSafetyService safety = new ChannelSafetyService(gemini, tagCatalog, mapper, properties.getGemini().getModel(),
				java.time.Clock.systemUTC());

		SafeHttp http = new SafeHttp(properties);
		PodcastDirectory directory = new PodcastDirectory(http, mapper, properties);
		PodcastFeedService feeds = new PodcastFeedService(http, null, properties, java.time.Clock.systemUTC());
		ParentProfileService profiles = mock(ParentProfileService.class);
		ParentProfile profile = new ParentProfile();
		profile.setChildAge(4);
		when(profiles.getProfile(PARENT)).thenReturn(profile);
		StagedChannelService staged = mock(StagedChannelService.class);
		when(staged.listStaged(PARENT)).thenReturn(List.of());
		CatalogService catalog = mock(CatalogService.class);
		when(catalog.findSource(any())).thenReturn(Optional.empty());

		AdkAgentEngine engine = new AdkAgentEngine(() -> com.google.adk.models.Gemini.builder()
				.modelName(properties.getGemini().getModel()).apiClient(client).build(), properties);
		return new AgentService(gemini, new ChannelLinkParser(), mock(YoutubeChannelLookup.class), mock(YoutubeVideoService.class),
				safety, staged, profiles, catalog, new PodcastLinkParser(), directory, feeds, mock(AgentRateLimiter.class),
				engine, adk);
	}

	private void storiesPodcast(boolean adk) throws Exception {
		String label = adk ? "adk" : "custom";
		AgentService service = build(adk);
		long started = System.nanoTime();
		AgentService.AgentReply reply = service.respond(PARENT, "add the podcast Stories Podcast", List.of());
		long ms = (System.nanoTime() - started) / 1_000_000;
		System.out.println("[live-agent] " + label + " (" + ms + " ms): " + reply.reply());
		reply.candidates().forEach(c -> System.out.println("[live-agent] " + label + " candidate: " + c.channelId() + " " + c.title()));

		assertThat(reply.reply()).isNotBlank().isNotEqualTo("Sorry, I got stuck on that one. Could you try again?");
		assertThat(reply.candidates()).as("screened candidates").allSatisfy(c -> assertThat(c.kind()).isEqualTo(ChannelFacts.PODCAST));
	}

	@Test
	void storiesPodcastOnTheAdkEngine() throws Exception {
		storiesPodcast(true);
	}

	@Test
	void storiesPodcastOnTheBuiltInLoop() throws Exception {
		storiesPodcast(false);
	}
}
