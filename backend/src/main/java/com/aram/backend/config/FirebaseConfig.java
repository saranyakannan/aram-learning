package com.aram.backend.config;

import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.util.StringUtils;

/** Firebase Admin, used only to verify parents' ID tokens. Created on first use so the app starts without it. */
@Configuration
public class FirebaseConfig {

	@Bean
	@Lazy
	public FirebaseAuth firebaseAuth(AramProperties properties) {
		String projectId = properties.getFirestore().getProjectId();
		if (!StringUtils.hasText(projectId)) {
			throw new IllegalStateException(
					"Sign-in needs a Google Cloud project: set aram.firestore.project-id in backend/config/application.yml");
		}
		FirebaseApp app = FirebaseApp.getApps().isEmpty()
				? FirebaseApp.initializeApp(FirebaseOptions.builder()
						.setProjectId(projectId)
						.setCredentials(GcpCredentials.forProject(projectId))
						.build())
				: FirebaseApp.getInstance();
		return FirebaseAuth.getInstance(app);
	}
}
