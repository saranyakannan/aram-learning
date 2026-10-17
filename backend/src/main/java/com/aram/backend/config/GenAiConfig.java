package com.aram.backend.config;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.util.StringUtils;

/** Gemini on Vertex AI. Created lazily so the app still starts when no project is configured. */
@Configuration
public class GenAiConfig {

	@Bean
	@Lazy
	public Client genAiClient(AramProperties properties) {
		String projectId = properties.getFirestore().getProjectId();
		if (!StringUtils.hasText(projectId)) {
			throw new IllegalStateException(
					"Gemini needs a Google Cloud project: set aram.firestore.project-id in backend/config/application.yml");
		}
		return Client.builder()
				.vertexAI(true)
				.project(projectId)
				.location(properties.getGemini().getLocation())
				.credentials(GcpCredentials.forProject(projectId))
				// Without limits a stalled request hangs for minutes (the SDK also retries internally with backoff),
				// so cap each attempt and let GeminiClient do the one bounded retry itself.
				.httpOptions(HttpOptions.builder()
						.timeout(properties.getGemini().getRequestTimeoutMillis())
						.retryOptions(HttpRetryOptions.builder().attempts(1).build())
						.build())
				.build();
	}
}
