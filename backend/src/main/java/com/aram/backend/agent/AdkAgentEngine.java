package com.aram.backend.agent;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.Gemini;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.adk.tools.Annotations;
import com.google.adk.tools.FunctionTool;
import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Part;
import com.google.genai.types.ThinkingConfig;
import io.reactivex.rxjava3.core.Flowable;
import com.aram.backend.config.AramProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Runs the Aram Agent on Google's Agent Development Kit. ADK owns the model loop (call Gemini, run the tools it asks for,
 * feed the results back); everything that matters for safety stays in the application's own tool bodies, which are the
 * same ones the hand-written loop uses. The tools are created per request, so the parent and the child's age are fixed
 * by the server and never come from the model.
 */
@Component
public class AdkAgentEngine {

	/** What the engine needs back from the app: run the named tool with these arguments. */
	@FunctionalInterface
	public interface ToolRunner extends BiFunction<String, Map<String, Object>, Map<String, Object>> {
	}

	/** Thrown for anything the caller should answer with the hand-written loop instead. */
	public static class AdkEngineException extends RuntimeException {
		public AdkEngineException(String message, Throwable cause) {
			super(message, cause);
		}
	}

	static final String APP_NAME = "aram-agent";
	static final String AGENT_NAME = "aram_agent";
	/** A turn may call tools at most this many times in total (the model loop has its own step limit as well). */
	static final int MAX_TOOL_CALLS_PER_TURN = AgentService.MAX_MODEL_STEPS * 2;
	private static final long TURN_TIMEOUT_SECONDS = 60;

	private static final Logger log = LoggerFactory.getLogger(AdkAgentEngine.class);

	private final Supplier<BaseLlm> model;
	private final AramProperties.Gemini settings;

	@Autowired
	public AdkAgentEngine(ObjectProvider<Client> client, AramProperties properties) {
		this.settings = properties.getGemini();
		this.model = () -> {
			Client genai = client.getObject();
			BaseLlm primary = Gemini.builder().modelName(settings.getModel()).apiClient(genai).build();
			String fallbackName = settings.getFallbackModel();
			if (fallbackName == null || fallbackName.isBlank()) {
				return primary;
			}
			return new FailoverLlm(primary, Gemini.builder().modelName(fallbackName).apiClient(genai).build(), settings);
		};
	}

	/** Test seam: run against any ADK model (a scripted fake). */
	AdkAgentEngine(Supplier<BaseLlm> model, AramProperties properties) {
		this.settings = properties.getGemini();
		this.model = model;
	}

	/**
	 * Runs one turn. {@code history} holds the earlier user/assistant text (already validated by the caller).
	 *
	 * @return the model's final text, or {@code null} when it ended without any
	 * @throws AdkEngineException when ADK or the model failed, so the caller can use the hand-written loop
	 */
	public String run(String instruction, List<Turn> history, String userText, ToolRunner tools) {
		try {
			BaseSessionService sessions = new InMemorySessionService();
			AdkTools adkTools = new AdkTools(tools);
			LlmAgent agent = LlmAgent.builder()
					.name(AGENT_NAME)
					.model(model.get())
					.instruction(instruction)
					.generateContentConfig(contentConfig())
					.maxSteps(AgentService.MAX_MODEL_STEPS)
					.tools(FunctionTool.create(adkTools, "find_channel"), FunctionTool.create(adkTools, "assess_channel"),
							FunctionTool.create(adkTools, "find_podcast"), FunctionTool.create(adkTools, "assess_podcast"),
							FunctionTool.create(adkTools, "recommend_from_catalog"))
					.build();
			Runner runner = new Runner(agent, APP_NAME, new InMemoryArtifactService(), sessions);

			String user = "turn";
			Session session = sessions.createSession(APP_NAME, user).blockingGet();
			for (Turn turn : history) {
				appendHistory(sessions, session, turn);
			}
			Content message = Content.builder().role("user").parts(Part.fromText(userText)).build();
			List<Event> events = runner.runAsync(user, session.id(), message, RunConfig.builder().maxLlmCalls(AgentService.MAX_MODEL_STEPS).build())
					.toList().timeout(TURN_TIMEOUT_SECONDS, TimeUnit.SECONDS).blockingGet();

			String reply = null;
			for (Event event : events) {
				if (event.finalResponse() && event.stringifyContent() != null && !event.stringifyContent().isBlank()) {
					reply = event.stringifyContent().strip();
				}
			}
			return reply;
		} catch (RuntimeException e) {
			throw new AdkEngineException("ADK run failed: " + e.getMessage(), e);
		}
	}

	private GenerateContentConfig contentConfig() {
		return GenerateContentConfig.builder().temperature(0.2f).maxOutputTokens(settings.getMaxOutputTokens())
				.thinkingConfig(thinkingFor(settings, settings.getModel())).build();
	}

	/** Gemini 3.x takes a thinking level; Gemini 2.5 takes a token budget and rejects a level. Keep both cheap. */
	private static ThinkingConfig thinkingFor(AramProperties.Gemini settings, String modelName) {
		return modelName.startsWith("gemini-2.5")
				? ThinkingConfig.builder().thinkingBudget(0).build()
				: ThinkingConfig.builder().thinkingLevel(settings.getThinkingLevel()).build();
	}

	/**
	 * The primary model, and the fallback model once the primary has failed (or stalled past the request timeout) in this
	 * run. After the first failure the rest of the run stays on the fallback, so a stalled primary costs one timeout, not one
	 * per step. The same policy the built-in loop uses.
	 */
	static final class FailoverLlm extends BaseLlm {

		private final BaseLlm primary;
		private final BaseLlm fallback;
		private final AramProperties.Gemini settings;
		private final java.util.concurrent.atomic.AtomicBoolean failedOver = new java.util.concurrent.atomic.AtomicBoolean();

		FailoverLlm(BaseLlm primary, BaseLlm fallback, AramProperties.Gemini settings) {
			super(primary.model());
			this.primary = primary;
			this.fallback = fallback;
			this.settings = settings;
		}

		@Override
		public Flowable<LlmResponse> generateContent(LlmRequest request, boolean stream) {
			if (failedOver.get()) {
				return onFallback(request, stream);
			}
			return primary.generateContent(request.toBuilder().model(primary.model()).build(), stream).onErrorResumeNext(error -> {
				log.warn("ADK model {} failed, continuing on {}: {}", primary.model(), fallback.model(), error.getMessage());
				failedOver.set(true);
				return onFallback(request, stream);
			});
		}

		private Flowable<LlmResponse> onFallback(LlmRequest request, boolean stream) {
			GenerateContentConfig config = request.config().orElse(GenerateContentConfig.builder().build()).toBuilder()
					.thinkingConfig(thinkingFor(settings, fallback.model())).build();
			return fallback.generateContent(request.toBuilder().model(fallback.model()).config(config).build(), stream);
		}

		@Override
		public BaseLlmConnection connect(LlmRequest request) {
			throw new UnsupportedOperationException("Live connections are not used");
		}
	}

	/** Only plain user/assistant text is ever replayed as history; tool steps of earlier turns are not kept. */
	private static void appendHistory(BaseSessionService sessions, Session session, Turn turn) {
		String author;
		String role;
		String text;
		if (turn instanceof Turn.UserText user) {
			author = "user";
			role = "user";
			text = user.text();
		} else if (turn instanceof Turn.ModelText model) {
			author = AGENT_NAME;
			role = "model";
			text = model.text();
		} else {
			return;
		}
		Event event = Event.builder().id(UUID.randomUUID().toString()).author(author)
				.content(Content.builder().role(role).parts(Part.fromText(text)).build())
				.timestamp(Instant.now().toEpochMilli()).build();
		sessions.appendEvent(session, event).blockingGet();
	}

	/**
	 * The four tools the model may call. Each one only forwards to the application's own tool body; the body decides
	 * what is allowed. A hard cap on calls per turn stops a runaway loop.
	 */
	public static class AdkTools {

		private final ToolRunner runner;
		private final AtomicInteger calls = new AtomicInteger();

		AdkTools(ToolRunner runner) {
			this.runner = runner;
		}

		private Map<String, Object> call(String tool, String argName, String argValue) {
			if (calls.incrementAndGet() > MAX_TOOL_CALLS_PER_TURN) {
				return Map.of("error", "Too many tool calls in one turn. Answer the parent with what you already have.");
			}
			return runner.apply(tool, Map.of(argName, argValue == null ? "" : argValue));
		}

		@Annotations.Schema(name = "find_channel", description = "Looks up a YouTube channel from whatever the parent typed: a link, an @handle, a channel id or a plain name. Links, handles and ids return one channel; a plain name returns up to 3 candidates that the parent must choose from.")
		public Map<String, Object> find_channel(
				@Annotations.Schema(name = "query", description = "Exactly what the parent typed to identify the channel") String query) {
			return call("find_channel", "query", query);
		}

		@Annotations.Schema(name = "assess_channel", description = "Runs the child-safety check on one channel (by YouTube channel id) for this parent's child. If it passes, the channel is automatically put into the parent's pending changes (the parent still has to press Save). Returns whether it was staged and why.")
		public Map<String, Object> assess_channel(
				@Annotations.Schema(name = "channelId", description = "A YouTube channel id starting with UC, taken from find_channel") String channelId) {
			return call("assess_channel", "channelId", channelId);
		}

		@Annotations.Schema(name = "find_podcast", description = "Looks up a podcast show in Apple Podcasts from whatever the parent typed: an Apple Podcasts link, a numeric id or a plain name. A link or id returns one show; a plain name returns up to 5 candidates that the parent must choose from. Spotify links are not supported.")
		public Map<String, Object> find_podcast(
				@Annotations.Schema(name = "query", description = "Exactly what the parent typed to identify the podcast") String query) {
			return call("find_podcast", "query", query);
		}

		@Annotations.Schema(name = "assess_podcast", description = "Runs the child-safety check on one podcast (by its Apple Podcasts id) for this parent's child. If it passes, the show is automatically put into the parent's pending changes (the parent still has to press Save). Shows marked explicit are always refused. Returns whether it was staged and why.")
		public Map<String, Object> assess_podcast(
				@Annotations.Schema(name = "podcastId", description = "An Apple Podcasts id (digits only), taken from find_podcast") String podcastId) {
			return call("assess_podcast", "podcastId", podcastId);
		}

		@Annotations.Schema(name = "recommend_from_catalog", description = "Suggests channels and podcasts from Aram's own trusted list (reviewed, and right for the child's age) for a topic the parent describes, such as phonics, bedtime stories, space or music. Use it when the parent asks for suggestions or recommendations instead of naming a specific channel. Returns up to 5 candidates that the parent must choose from. It never searches the web.")
		public Map<String, Object> recommend_from_catalog(
				@Annotations.Schema(name = "topic", description = "The topic the parent wants suggestions for, in their own words, e.g. \"phonics\" or \"bedtime stories podcast\"") String topic) {
			return call("recommend_from_catalog", "topic", topic);
		}
	}
}
