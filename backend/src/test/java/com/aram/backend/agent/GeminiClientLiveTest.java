package com.aram.backend.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Calls the real Gemini API (costs a few tenths of a cent). Skipped unless run as:
 * {@code ARAM_LIVE_GEMINI=true ARAM_LIVE_PROJECT=<gcp project> ./gradlew test --tests '*GeminiClientLiveTest' --info}
 */
@EnabledIfEnvironmentVariable(named = "ARAM_LIVE_GEMINI", matches = "true")
class GeminiClientLiveTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	private GeminiClient liveClient() throws IOException {
		return LiveGemini.client();
	}

	@Test
	void returnsSchemaShapedJson() throws Exception {
		Map<String, Object> schema = Map.of(
				"type", "object",
				"properties", Map.of(
						"suitable", Map.of("type", "boolean"),
						"reason", Map.of("type", "string")),
				"required", List.of("suitable", "reason"));

		JsonModel.JsonResponse response = liveClient().generateJson(
				"You judge whether children's channels suit a given age.",
				"Is an animated counting show for ages 2-6 suitable for a 4-year-old?",
				schema);
		String json = response.json();

		JsonNode node = objectMapper.readTree(json);
		System.out.println("[live] generateJson (" + response.model() + ") -> " + json);
		assertThat(node.get("suitable").asBoolean()).isTrue();
		assertThat(node.get("reason").asText()).isNotBlank();
	}

	@Test
	void callsAToolThenAnswersFromItsResult() throws Exception {
		GeminiClient gemini = liveClient();
		ToolSpec lookup = new ToolSpec("lookup_channel", "Finds a YouTube channel by name.", Map.of(
				"type", "object",
				"properties", Map.of("query", Map.of("type", "string", "description", "channel name")),
				"required", List.of("query")));
		String system = "You help a parent add YouTube channels. Always call lookup_channel before answering.";

		ModelStep first = gemini.nextStep(system, List.of(new Turn.UserText("Please add the Numberblocks channel")),
				List.of(lookup));
		System.out.println("[live] step 1 tool calls -> " + first.toolCalls());
		assertThat(first.toolCalls()).hasSize(1);
		ToolCall call = first.toolCalls().get(0);
		assertThat(call.name()).isEqualTo("lookup_channel");
		assertThat(String.valueOf(call.args().get("query")).toLowerCase()).contains("numberblocks");

		ModelStep second = gemini.nextStep(system, List.of(
				new Turn.UserText("Please add the Numberblocks channel"),
				new Turn.ModelStepTurn(first),
				new Turn.ToolResults(List.of(new ToolResult(call.id(), call.name(),
						Map.of("name", "Numberblocks", "handle", "@Numberblocks", "subscribers", 1500000))))),
				List.of(lookup));
		System.out.println("[live] step 2 text -> " + second.text());
		assertThat(second.toolCalls()).isEmpty();
		assertThat(second.text()).containsIgnoringCase("numberblocks");
	}

	@Test
	void aRequestThatCannotFinishInTimeFailsFastInsteadOfHanging() throws Exception {
		GeminiClient impatient = LiveGemini.client(1);
		long started = System.nanoTime();

		assertThatThrownBy(() -> impatient.generateJson("Answer in JSON.", "Say hi.",
				Map.of("type", "object", "properties", Map.of("hi", Map.of("type", "string")))))
				.isInstanceOf(ModelException.class);

		long seconds = (System.nanoTime() - started) / 1_000_000_000;
		System.out.println("[live] 1 ms timeout failed after " + seconds + " s");
		assertThat(seconds).isLessThan(10);
	}

	@Test
	void theFallbackModelCanContinueAToolConversationTheFirstModelStarted() throws Exception {
		GeminiClient primary = liveClient();
		GeminiClient fallback = LiveGemini.client(10_000, "gemini-2.5-flash");
		ToolSpec lookup = new ToolSpec("lookup_channel", "Finds a YouTube channel by name.", Map.of(
				"type", "object",
				"properties", Map.of("query", Map.of("type", "string")),
				"required", List.of("query")));
		String system = "You help a parent add YouTube channels. Always call lookup_channel before answering.";

		ModelStep first = primary.nextStep(system, List.of(new Turn.UserText("Please add Numberblocks")), List.of(lookup));
		assertThat(first.toolCalls()).hasSize(1);
		ToolCall call = first.toolCalls().get(0);

		ModelStep second = fallback.nextStep(system, List.of(
				new Turn.UserText("Please add Numberblocks"),
				new Turn.ModelStepTurn(first),
				new Turn.ToolResults(List.of(new ToolResult(call.id(), call.name(), Map.of("name", "Numberblocks"))))),
				List.of(lookup));

		System.out.println("[live] fallback continued -> " + second.text());
		assertThat(second.toolCalls()).isEmpty();
		assertThat(second.text()).containsIgnoringCase("numberblocks");
	}
}
