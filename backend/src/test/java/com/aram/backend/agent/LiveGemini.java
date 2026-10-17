package com.aram.backend.agent;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import com.aram.backend.config.AramProperties;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/** Builds a real Gemini client for the opt-in live tests (see {@link GeminiClientLiveTest}). */
final class LiveGemini {

	private LiveGemini() {
	}

	static GeminiClient client() throws IOException {
		return client(new AramProperties().getGemini().getRequestTimeoutMillis());
	}

	static GeminiClient client(int timeoutMillis) throws IOException {
		return client(timeoutMillis, System.getenv("ARAM_LIVE_MODEL"));
	}

	static GeminiClient client(int timeoutMillis, String modelOverride) throws IOException {
		String project = System.getenv("ARAM_LIVE_PROJECT");
		assertThat(project).as("ARAM_LIVE_PROJECT").isNotBlank();
		AramProperties properties = new AramProperties();
		if (modelOverride != null) {
			properties.getGemini().setModel(modelOverride);
		}
		Client client = Client.builder()
				.vertexAI(true)
				.project(project)
				.location(properties.getGemini().getLocation())
				.credentials(GoogleCredentials.getApplicationDefault().createWithQuotaProject(project))
				.httpOptions(HttpOptions.builder().timeout(timeoutMillis)
						.retryOptions(HttpRetryOptions.builder().attempts(1).build()).build())
				.build();
		return new GeminiClient(() -> client, properties);
	}
}
