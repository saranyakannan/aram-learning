package com.aram.backend.config;

import com.google.auth.oauth2.GoogleCredentials;

import java.io.IOException;

/** Application Default Credentials, billed to the given project rather than the machine-wide quota project. */
final class GcpCredentials {

	private GcpCredentials() {
	}

	static GoogleCredentials forProject(String projectId) {
		try {
			return GoogleCredentials.getApplicationDefault().createWithQuotaProject(projectId);
		} catch (IOException e) {
			throw new IllegalStateException(
					"No Application Default Credentials found. Run: gcloud auth application-default login", e);
		}
	}
}
