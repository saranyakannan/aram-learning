package com.aram.backend.agent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeminiClientRetryTest {

	@Test
	void aFailingPrimaryFallsBackToTheFallbackModelAndSaysSo() {
		List<String> tried = new ArrayList<>();

		GeminiClient.Outcome<String> outcome = GeminiClient.runWithFallback("primary", "fallback", 2, model -> {
			tried.add(model);
			if (model.equals("primary")) {
				throw new IllegalStateException("stalled");
			}
			return "answer";
		});

		assertThat(outcome.value()).isEqualTo("answer");
		assertThat(outcome.model()).isEqualTo("fallback");
		assertThat(tried).containsExactly("primary", "fallback");
	}

	@Test
	void aHealthyPrimaryIsUsedOnceAndTheFallbackNeverTouched() {
		List<String> tried = new ArrayList<>();

		GeminiClient.Outcome<String> outcome = GeminiClient.runWithFallback("primary", "fallback", 2, model -> {
			tried.add(model);
			return "answer";
		});

		assertThat(outcome.model()).isEqualTo("primary");
		assertThat(tried).containsExactly("primary");
	}

	@Test
	void withoutAFallbackTheSameModelIsRetried() {
		List<String> tried = new ArrayList<>();

		GeminiClient.runWithFallback("primary", "", 2, model -> {
			tried.add(model);
			if (tried.size() == 1) {
				throw new IllegalStateException("blip");
			}
			return "ok";
		});

		assertThat(tried).containsExactly("primary", "primary");
	}

	@Test
	void theLastFailureIsThrownOnceAllAttemptsFail() {
		List<String> tried = new ArrayList<>();

		assertThatThrownBy(() -> GeminiClient.runWithFallback("primary", "fallback", 2, model -> {
			tried.add(model);
			throw new IllegalStateException("down: " + model);
		})).hasMessage("down: fallback");
		assertThat(tried).containsExactly("primary", "fallback");
	}

	@Test
	void atLeastOneAttemptIsAlwaysMade() {
		assertThat(GeminiClient.runWithFallback("primary", "fallback", 0, model -> model).value()).isEqualTo("primary");
	}
}
