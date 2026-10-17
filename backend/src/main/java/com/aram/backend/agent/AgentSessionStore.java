package com.aram.backend.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.Session;
import com.google.adk.sessions.SessionNotFoundException;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.aram.backend.config.AramProperties;
import com.aram.backend.exception.ConversationBusyException;
import com.aram.backend.exception.ConversationNotFoundException;
import com.aram.backend.model.ChannelFacts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The parent's conversation with the agent, kept on the server as an ADK session (Firestore in production). A session is
 * stored under the signed-in parent's id, so another parent's session id is simply "not found". Only the plain chat text
 * is stored (what the parent typed and what Aram answered), plus the last candidate cards so a reload shows them again;
 * the model's tool traffic and the third-party text inside it are not persisted. Conversations idle for longer than the
 * retention period are deleted when the parent next opens the agent. Nothing here is ever exported to BigQuery.
 */
@Component
public class AgentSessionStore {

	public static final String APP_NAME = "aram-agent";
	static final String CANDIDATES_KEY = "lastCandidates";
	static final int MAX_SHOWN = 40;
	private static final Pattern SESSION_ID = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");
	private static final TypeReference<List<ChannelFacts>> CANDIDATE_LIST = new TypeReference<>() {
	};

	/** What the page needs to continue a conversation: the text so far and the cards from the last answer. */
	public record Transcript(String sessionId, List<AgentService.ChatMessage> messages, List<ChannelFacts> candidates) {
	}

	private static final Logger log = LoggerFactory.getLogger(AgentSessionStore.class);

	private final BaseSessionService sessions;
	private final ObjectMapper mapper;
	private final Clock clock;
	private final Duration retention;
	private final ConcurrentHashMap<String, Semaphore> locks = new ConcurrentHashMap<>();

	public AgentSessionStore(BaseSessionService sessions, ObjectMapper mapper, Clock clock, AramProperties properties) {
		this.sessions = sessions;
		this.mapper = mapper;
		this.clock = clock;
		this.retention = Duration.ofDays(Math.max(1, properties.getAgent().getSessionRetentionDays()));
	}

	public Session create(String parentId) {
		return sessions.createSession(APP_NAME, parentId, new ConcurrentHashMap<>(), UUID.randomUUID().toString()).blockingGet();
	}

	/** The parent's own session, or {@link ConversationNotFoundException} for an unknown, expired or someone else's id. */
	public Session find(String parentId, String sessionId) {
		if (sessionId == null || !SESSION_ID.matcher(sessionId).matches()) {
			throw new ConversationNotFoundException();
		}
		try {
			Session session = sessions.getSession(APP_NAME, parentId, sessionId, Optional.empty()).blockingGet();
			if (session == null || !parentId.equals(session.userId())) {
				throw new ConversationNotFoundException();
			}
			if (isExpired(session)) {
				deleteQuietly(parentId, sessionId);
				throw new ConversationNotFoundException();
			}
			return session;
		} catch (SessionNotFoundException e) {
			throw new ConversationNotFoundException();
		}
	}

	/** The most recent conversation still within the retention period; older ones are deleted on the way. */
	public Optional<Transcript> latest(String parentId) {
		List<Session> mine = new ArrayList<>(sessions.listSessions(APP_NAME, parentId).blockingGet().sessions());
		Session newest = null;
		for (Session session : mine) {
			if (!parentId.equals(session.userId())) {
				continue;
			}
			if (isExpired(session)) {
				deleteQuietly(parentId, session.id());
			} else if (newest == null || session.lastUpdateTime().isAfter(newest.lastUpdateTime())) {
				newest = session;
			}
		}
		if (newest == null) {
			return Optional.empty();
		}
		return Optional.of(transcript(find(parentId, newest.id())));
	}

	public void delete(String parentId, String sessionId) {
		find(parentId, sessionId);
		sessions.deleteSession(APP_NAME, parentId, sessionId).blockingAwait();
	}

	/** Earlier messages of this conversation as plain user/assistant text. */
	public List<AgentService.ChatMessage> history(Session session) {
		return messages(session);
	}

	public Transcript transcript(Session session) {
		List<AgentService.ChatMessage> messages = messages(session);
		if (messages.size() > MAX_SHOWN) {
			messages = messages.subList(messages.size() - MAX_SHOWN, messages.size());
		}
		return new Transcript(session.id(), messages, candidates(session));
	}

	/** Stores one finished turn: what the parent typed, what Aram answered, and the cards that came with the answer. */
	public void recordTurn(Session session, String userText, String reply, List<ChannelFacts> candidates) {
		long now = clock.millis();
		sessions.appendEvent(session, textEvent("user", "user", userText, now, null)).blockingGet();
		Map<String, Object> state = new ConcurrentHashMap<>();
		state.put(CANDIDATES_KEY, toJson(candidates));
		sessions.appendEvent(session, textEvent(AdkAgentEngine.AGENT_NAME, "model", reply, now + 1, state)).blockingGet();
	}

	/** One request at a time per conversation; a second one is refused instead of interleaving. */
	public <T> T withLock(String parentId, String sessionId, Supplier<T> work) {
		if (locks.size() > 1000) {
			locks.values().removeIf(lock -> lock.availablePermits() == 1);
		}
		Semaphore lock = locks.computeIfAbsent(parentId + "/" + sessionId, key -> new Semaphore(1));
		if (!lock.tryAcquire()) {
			throw new ConversationBusyException();
		}
		try {
			return work.get();
		} finally {
			lock.release();
		}
	}

	private boolean isExpired(Session session) {
		Instant updated = session.lastUpdateTime();
		return updated != null && updated.isBefore(clock.instant().minus(retention));
	}

	private void deleteQuietly(String parentId, String sessionId) {
		try {
			sessions.deleteSession(APP_NAME, parentId, sessionId).blockingAwait();
		} catch (RuntimeException e) {
			log.warn("Could not delete an expired agent conversation: {}", e.getMessage());
		}
	}

	private static Event textEvent(String author, String role, String text, long timestamp, Map<String, Object> stateDelta) {
		Event.Builder event = Event.builder().id(UUID.randomUUID().toString()).author(author)
				.content(Content.builder().role(role).parts(Part.fromText(text)).build()).timestamp(timestamp);
		if (stateDelta != null) {
			event.actions(EventActions.builder().stateDelta(new ConcurrentHashMap<>(stateDelta)).build());
		}
		return event.build();
	}

	private static List<AgentService.ChatMessage> messages(Session session) {
		List<AgentService.ChatMessage> messages = new ArrayList<>();
		// Firestore hands the "user" author back as the user's id, so the speaker is read from the content role.
		session.events().forEach(event -> {
			String text = event.stringifyContent();
			if (text == null || text.isBlank()) {
				return;
			}
			String role = event.content().flatMap(Content::role).orElse("");
			if ("user".equals(role)) {
				messages.add(new AgentService.ChatMessage("user", text));
			} else if ("model".equals(role)) {
				messages.add(new AgentService.ChatMessage("assistant", text));
			}
		});
		return messages;
	}

	private List<ChannelFacts> candidates(Session session) {
		Object stored = session.state().get(CANDIDATES_KEY);
		if (!(stored instanceof String json) || json.isBlank()) {
			return List.of();
		}
		try {
			return mapper.readValue(json, CANDIDATE_LIST);
		} catch (Exception e) {
			log.warn("Ignoring unreadable stored candidates: {}", e.getMessage());
			return List.of();
		}
	}

	private String toJson(List<ChannelFacts> candidates) {
		try {
			return mapper.writeValueAsString(candidates == null ? List.of() : candidates);
		} catch (Exception e) {
			return "[]";
		}
	}
}
