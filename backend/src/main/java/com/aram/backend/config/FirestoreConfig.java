package com.aram.backend.config;

import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Builds the Firestore client using Application Default Credentials: {@code gcloud auth
 * application-default login} locally, or the Cloud Run service account in production.
 */
@Configuration
public class FirestoreConfig {

	private static final Logger log = LoggerFactory.getLogger(FirestoreConfig.class);

	@Bean
	public Firestore firestore(AramProperties properties) {
		FirestoreOptions.Builder options = FirestoreOptions.getDefaultInstance().toBuilder();
		String projectId = properties.getFirestore().getProjectId();

		if (StringUtils.hasText(projectId)) {
			options.setProjectId(projectId);
			options.setCredentials(GcpCredentials.forProject(projectId));
		} else {
			log.warn("aram.firestore.project-id is not set; falling back to the project detected from the "
					+ "environment, which changes whenever the active gcloud project does. "
					+ "Set it in backend/config/application.yml.");
		}

		FirestoreOptions built = options.build();
		log.info("Firestore project: {}", built.getProjectId());
		return built.getService();
	}
}
