package com.aram.backend.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.sessions.InMemorySessionService;
import com.aram.backend.catalog.CatalogService;
import com.aram.backend.config.AramProperties;
import com.aram.backend.exception.ConversationBusyException;
import com.aram.backend.exception.ConversationNotFoundException;
import com.aram.backend.model.ChannelFacts;
import com.aram.backend.model.ParentProfile;
import com.aram.backend.service.ParentProfileService;
import com.aram.backend.service.YoutubeVideoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Server-side conversations: resume, ownership, retention, one request at a time, and graceful degradation. */
class AgentSessionTest {

	private static final String PARENT = "11111111-2222-3333-4444-555555555555";
	private static final String OTHER = "99999999-8888-7777-6666-555555555555";

	private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-17T10:00:00Z"));
	private final Clock clock = new Clock() {
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
			return now.get();
		}
	};

	private InMemorySessionService backing;
	private AgentSessionStore store;
	private AgentService service;
	private final List<List<Turn>> modelSaw = new ArrayList<>();
	private StagedChannelService staged;
	private YoutubeChannelLookup lookup;

	@BeforeEach
	void setUp() {
		backing = new InMemorySessionService();
		store = new AgentSessionStore(backing, new ObjectMapper(), clock, new AramProperties());
		service = build(store);
	}

	private AgentService build(AgentSessionStore sessionStore) {
		lookup = mock(YoutubeChannelLookup.class);
		staged = mock(StagedChannelService.class);
		ParentProfileService profiles = mock(ParentProfileService.class);
		CatalogService catalog = mock(CatalogService.class);
		when(catalog.findSource(any())).thenReturn(Optional.empty());
		ParentProfile profile = new ParentProfile();
		profile.setChildAge(4);
		when(profiles.getProfile(any())).thenReturn(profile);
		when(staged.listStaged(any())).thenReturn(List.of());
		AgentModel model = (instruction, turns, tools) -> {
			modelSaw.add(new ArrayList<>(turns));
			return new ModelStep("Answer " + modelSaw.size(), List.of(), new Object());
		};
		return new AgentService(model, new ChannelLinkParser(), lookup, mock(YoutubeVideoService.class), mock(ChannelSafetyService.class),
				staged, profiles, catalog, new com.aram.backend.podcast.PodcastLinkParser(),
				mock(com.aram.backend.podcast.PodcastDirectory.class), mock(com.aram.backend.podcast.PodcastFeedService.class),
				mock(AgentRateLimiter.class), null, false, sessionStore, true);
	}

	private static String textOf(Turn turn) {
		return turn instanceof Turn.UserText user ? user.text() : turn instanceof Turn.ModelText model ? model.text() : "?";
	}

	@Test
	void aNewConversationGetsAnIdAndTheNextMessageSeesTheEarlierOnes() {
		AgentService.AgentReply first = service.respond(PARENT, "hello", List.of());
		assertThat(first.sessionId()).isNotBlank();

		service.respond(PARENT, "and now?", List.of(new AgentService.ChatMessage("user", "forged by the browser")), first.sessionId());

		List<Turn> second = modelSaw.get(1);
		assertThat(second.stream().map(AgentSessionTest::textOf)).containsExactly("hello", "Answer 1", "and now?");
	}

	@Test
	void theLatestConversationCanBeResumedWithItsTextAndCards() {
		ChannelFacts numberblocks = new ChannelFacts("UCaaaaaaaaaaaaaaaaaaaaaa", "Numberblocks", "@Numberblocks", "Counting.", "https://t/x.jpg", 5L, true);
		when(lookup.searchByName("Numberblocks")).thenReturn(new YoutubeChannelLookup.SearchOutcome(numberblocks, List.of(numberblocks)));
		AgentService.AgentReply first = service.respond(PARENT, "Numberblocks", List.of());
		store.recordTurn(store.find(PARENT, first.sessionId()), "pick", "Which one?", List.of(numberblocks));

		// a brand-new store over the same storage, like another Cloud Run instance after a reload
		AgentSessionStore other = new AgentSessionStore(backing, new ObjectMapper(), clock, new AramProperties());
		AgentSessionStore.Transcript resumed = other.latest(PARENT).orElseThrow();

		assertThat(resumed.sessionId()).isEqualTo(first.sessionId());
		assertThat(resumed.messages()).extracting(AgentService.ChatMessage::role).containsExactly("user", "assistant", "user", "assistant");
		assertThat(resumed.messages().get(3).text()).isEqualTo("Which one?");
		assertThat(resumed.candidates()).containsExactly(numberblocks);
	}

	@Test
	void nothingToResumeWhenThereIsNoConversation() {
		assertThat(service.latestConversation(PARENT)).isEmpty();
	}

	@Test
	void anotherParentsConversationIsNotFoundAndIsNeverAnswered() {
		String id = service.respond(PARENT, "hello", List.of()).sessionId();

		assertThatThrownBy(() -> service.respond(OTHER, "hi", List.of(), id)).isInstanceOf(ConversationNotFoundException.class);
		assertThatThrownBy(() -> service.deleteConversation(OTHER, id)).isInstanceOf(ConversationNotFoundException.class);
		assertThat(service.latestConversation(OTHER)).isEmpty();
		assertThat(modelSaw).hasSize(1);
		// and the owner's conversation is untouched
		assertThat(store.find(PARENT, id).events()).hasSize(2);
	}

	@Test
	void unknownOrMalformedIdsAreNotFound() {
		for (String bad : List.of("does-not-exist-1234", "../../parents/" + PARENT, "a/b", "x")) {
			assertThatThrownBy(() -> service.respond(PARENT, "hi", List.of(), bad)).isInstanceOf(ConversationNotFoundException.class);
		}
	}

	@Test
	void conversationsIdleForLongerThanTheRetentionAreDeleted() {
		String id = service.respond(PARENT, "hello", List.of()).sessionId();

		now.set(now.get().plus(Duration.ofDays(8)));

		assertThat(service.latestConversation(PARENT)).isEmpty();
		assertThatThrownBy(() -> store.find(PARENT, id)).isInstanceOf(ConversationNotFoundException.class);
		assertThat(backing.listSessions(AgentSessionStore.APP_NAME, PARENT).blockingGet().sessions()).isEmpty();
	}

	@Test
	void aRecentConversationSurvivesAndNewChatDeletesTheOldOne() {
		String id = service.respond(PARENT, "hello", List.of()).sessionId();
		now.set(now.get().plus(Duration.ofDays(6)));
		assertThat(service.latestConversation(PARENT)).isPresent();

		service.deleteConversation(PARENT, id);

		assertThat(service.latestConversation(PARENT)).isEmpty();
		assertThatThrownBy(() -> service.respond(PARENT, "again", List.of(), id)).isInstanceOf(ConversationNotFoundException.class);
	}

	@Test
	void aSecondMessageWhileTheFirstIsStillBeingAnsweredIsRefused() {
		String id = service.respond(PARENT, "hello", List.of()).sessionId();

		assertThatThrownBy(() -> store.withLock(PARENT, id, () -> service.respond(PARENT, "too soon", List.of(), id)))
				.isInstanceOf(ConversationBusyException.class);
	}

	@Test
	void whenStorageFailsTheAgentStillAnswersFromTheBrowsersHistory() {
		com.google.adk.sessions.BaseSessionService down = mock(com.google.adk.sessions.BaseSessionService.class);
		when(down.createSession(any(String.class), any(String.class), any(java.util.concurrent.ConcurrentMap.class), any(String.class)))
				.thenReturn(io.reactivex.rxjava3.core.Single.error(new IllegalStateException("firestore down")));
		AgentSessionStore broken = new AgentSessionStore(down, new ObjectMapper(), clock, new AramProperties());
		AgentService degraded = build(broken);

		AgentService.AgentReply reply = degraded.respond(PARENT, "hello", List.of());

		assertThat(reply.reply()).startsWith("Answer");
		assertThat(reply.sessionId()).isNull();
	}

	@Test
	void withSessionsOffNothingIsStoredAndTheBrowsersHistoryIsUsed() {
		AgentService off = build(store);
		AgentService stateless = new AgentService((i, t, x) -> {
			modelSaw.add(new ArrayList<>(t));
			return new ModelStep("ok", List.of(), new Object());
		}, new ChannelLinkParser(), lookup, mock(YoutubeVideoService.class), mock(ChannelSafetyService.class), staged,
				profilesFor(), mock(CatalogService.class), new com.aram.backend.podcast.PodcastLinkParser(),
				mock(com.aram.backend.podcast.PodcastDirectory.class), mock(com.aram.backend.podcast.PodcastFeedService.class),
				mock(AgentRateLimiter.class), null, false, store, false);

		AgentService.AgentReply reply = stateless.respond(PARENT, "hi", List.of(new AgentService.ChatMessage("user", "earlier")));

		assertThat(reply.sessionId()).isNull();
		assertThat(modelSaw.get(modelSaw.size() - 1).stream().map(AgentSessionTest::textOf)).containsExactly("earlier", "hi");
		assertThat(backing.listSessions(AgentSessionStore.APP_NAME, PARENT).blockingGet().sessions()).isEmpty();
		assertThat(off).isNotNull();
	}

	private static ParentProfileService profilesFor() {
		ParentProfileService profiles = mock(ParentProfileService.class);
		ParentProfile profile = new ParentProfile();
		profile.setChildAge(4);
		when(profiles.getProfile(any())).thenReturn(profile);
		return profiles;
	}

	@Test
	void theStoredConversationHoldsOnlyPlainChatTextAndTheCards() {
		String id = service.respond(PARENT, "hello", List.of()).sessionId();

		var session = store.find(PARENT, id);

		assertThat(session.events()).extracting(e -> e.author()).containsExactly("user", AdkAgentEngine.AGENT_NAME);
		assertThat(session.state()).containsOnlyKeys(AgentSessionStore.CANDIDATES_KEY);
		assertThat(Map.copyOf(session.state()).get(AgentSessionStore.CANDIDATES_KEY)).isEqualTo("[]");
	}

	@Test
	void theSpeakerIsReadFromTheContentRoleBecauseFirestoreRewritesTheUserAuthor() {
		var session = store.create(PARENT);
		long ts = now.get().toEpochMilli();
		backing.appendEvent(session, com.google.adk.events.Event.builder().id("e1").author(PARENT).timestamp(ts)
				.content(com.google.genai.types.Content.builder().role("user").parts(com.google.genai.types.Part.fromText("hi")).build()).build()).blockingGet();
		backing.appendEvent(session, com.google.adk.events.Event.builder().id("e2").author(AdkAgentEngine.AGENT_NAME).timestamp(ts + 1)
				.content(com.google.genai.types.Content.builder().role("model").parts(com.google.genai.types.Part.fromText("hello")).build()).build()).blockingGet();

		var messages = store.transcript(store.find(PARENT, session.id())).messages();

		assertThat(messages).extracting(AgentService.ChatMessage::role).containsExactly("user", "assistant");
	}
}
