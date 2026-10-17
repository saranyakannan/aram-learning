package com.aram.backend;

import com.google.api.services.youtube.YouTube;
import com.google.cloud.firestore.Firestore;
import com.google.genai.Client;
import com.aram.backend.agent.AgentController;
import com.aram.backend.agent.AgentModel;
import com.aram.backend.agent.JsonModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the real Spring context with only the three Google clients replaced by mocks, so wiring mistakes
 * (ambiguous constructors, proxying a final class, a missing bean) fail here instead of at startup.
 */
@SpringBootTest
class ApplicationWiringTest {

	@MockitoBean
	Firestore firestore;

	@MockitoBean
	YouTube youTube;

	@MockitoBean
	Client genAiClient;

	@Autowired
	ApplicationContext context;

	@Test
	void theWholeApplicationContextStartsAndTheAgentIsWired() {
		assertThat(context.getBean(AgentController.class)).isNotNull();
		assertThat(context.getBeansOfType(AgentModel.class)).hasSize(1);
		assertThat(context.getBeansOfType(JsonModel.class)).hasSize(1);
	}
}
