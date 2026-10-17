package com.aram.backend.agent;

import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import com.google.genai.types.ThinkingConfig;
import com.google.genai.types.Tool;
import com.aram.backend.config.AramProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/** Gemini on Vertex AI behind the small {@link AgentModel} / {@link JsonModel} interfaces. */
@Component
public class GeminiClient implements AgentModel, JsonModel {

	private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);

	private final Supplier<Client> client;
	private final AramProperties.Gemini settings;

	/** The SDK client is built on first use, so the app still starts when Gemini is not configured. */
	@Autowired
	public GeminiClient(ObjectProvider<Client> client, AramProperties properties) {
		this(client::getObject, properties);
	}

	GeminiClient(Supplier<Client> client, AramProperties properties) {
		this.client = client;
		this.settings = properties.getGemini();
	}

	@Override
	public JsonResponse generateJson(String systemInstruction, String userPrompt, Map<String, Object> jsonSchema) {
		GenerateContentConfig.Builder config = baseConfig(systemInstruction)
				.temperature(0f)
				.responseMimeType("application/json")
				.responseJsonSchema(jsonSchema);
		Outcome<GenerateContentResponse> outcome = call(List.of(userContent(userPrompt)), config);

		String text = outcome.value().text();
		if (text == null || text.isBlank()) {
			throw new ModelException("Gemini returned no text (finish reason: " + outcome.value().finishReason() + ")");
		}
		return new JsonResponse(text, outcome.model());
	}

	@Override
	public ModelStep nextStep(String systemInstruction, List<Turn> turns, List<ToolSpec> tools) {
		Tool toolbox = Tool.builder()
				.functionDeclarations(tools.stream().map(GeminiClient::declaration).toList())
				.build();
		GenerateContentConfig.Builder config = baseConfig(systemInstruction).temperature(0.2f).tools(toolbox);
		GenerateContentResponse response = call(toContents(turns), config).value();

		List<ToolCall> calls = new ArrayList<>();
		for (FunctionCall functionCall : response.functionCalls()) {
			calls.add(new ToolCall(
					functionCall.id().orElse(null),
					functionCall.name().orElse(""),
					functionCall.args().orElse(Map.of())));
		}
		Content raw = response.candidates()
				.flatMap(candidates -> candidates.stream().findFirst())
				.flatMap(candidate -> candidate.content())
				.orElse(null);
		return new ModelStep(response.text(), List.copyOf(calls), raw);
	}

	private GenerateContentConfig.Builder baseConfig(String systemInstruction) {
		return GenerateContentConfig.builder()
				.systemInstruction(Content.fromParts(Part.fromText(systemInstruction)))
				.maxOutputTokens(settings.getMaxOutputTokens());
	}

	/** Gemini 3.x takes a thinking level; Gemini 2.5 takes a token budget and rejects a level. Keep both cheap. */
	private ThinkingConfig thinkingFor(String model) {
		return model.startsWith("gemini-2.5")
				? ThinkingConfig.builder().thinkingBudget(0).build()
				: ThinkingConfig.builder().thinkingLevel(settings.getThinkingLevel()).build();
	}

	record Outcome<T>(T value, String model) {
	}

	private Outcome<GenerateContentResponse> call(List<Content> contents, GenerateContentConfig.Builder config) {
		try {
			Outcome<GenerateContentResponse> outcome = runWithFallback(settings.getModel(), settings.getFallbackModel(),
					settings.getMaxAttempts(), model -> client.get().models.generateContent(model, contents,
							config.thinkingConfig(thinkingFor(model)).build()));
			logUsage(outcome.model(), outcome.value());
			return outcome;
		} catch (RuntimeException e) {
			throw new ModelException("Gemini request failed: " + e.getMessage(), e);
		}
	}

	/**
	 * Tries {@code primary} first; if it fails or stalls past the request timeout, the next attempts use
	 * {@code fallback} (or {@code primary} again when no fallback is configured).
	 */
	static <T> Outcome<T> runWithFallback(String primary, String fallback, int attempts, Function<String, T> action) {
		RuntimeException last = null;
		int total = Math.max(1, attempts);
		for (int attempt = 1; attempt <= total; attempt++) {
			String model = attempt == 1 || fallback == null || fallback.isBlank() ? primary : fallback;
			try {
				return new Outcome<>(action.apply(model), model);
			} catch (RuntimeException e) {
				last = e;
				log.warn("Gemini attempt {}/{} on {} failed: {}", attempt, total, model, e.getMessage());
			}
		}
		throw last;
	}

	private void logUsage(String model, GenerateContentResponse response) {
		GenerateContentResponseUsageMetadata usage = response.usageMetadata().orElse(null);
		if (usage != null) {
			log.info("Gemini {} usage: prompt={} thoughts={} output={}", model,
					usage.promptTokenCount().orElse(0), usage.thoughtsTokenCount().orElse(0),
					usage.candidatesTokenCount().orElse(0));
		}
	}

	private static FunctionDeclaration declaration(ToolSpec tool) {
		return FunctionDeclaration.builder()
				.name(tool.name())
				.description(tool.description())
				.parametersJsonSchema(tool.parametersSchema())
				.build();
	}

	private static List<Content> toContents(List<Turn> turns) {
		List<Content> contents = new ArrayList<>();
		for (Turn turn : turns) {
			if (turn instanceof Turn.UserText user) {
				contents.add(userContent(user.text()));
			} else if (turn instanceof Turn.ModelText model) {
				contents.add(Content.builder().role("model").parts(Part.fromText(model.text())).build());
			} else if (turn instanceof Turn.ModelStepTurn modelStep) {
				if (!(modelStep.step().raw() instanceof Content raw)) {
					throw new IllegalStateException("Model step has no provider message to replay");
				}
				contents.add(raw);
			} else if (turn instanceof Turn.ToolResults results) {
				contents.add(Content.builder().role("user")
						.parts(results.results().stream()
								.map(result -> Part.fromFunctionResponse(result.name(), result.response()))
								.toList())
						.build());
			}
		}
		return contents;
	}

	private static Content userContent(String text) {
		return Content.builder().role("user").parts(Part.fromText(text)).build();
	}
}
