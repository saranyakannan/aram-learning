package com.aram.backend.config;

import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.FirestoreSessionService;
import com.google.cloud.firestore.Firestore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The Aram Agent's conversations are ADK sessions stored in the same Firestore database (collection {@code adk-session}). */
@Configuration
public class AgentSessionConfig {

	@Bean
	public BaseSessionService agentSessionService(Firestore firestore) {
		return new FirestoreSessionService(firestore);
	}
}
