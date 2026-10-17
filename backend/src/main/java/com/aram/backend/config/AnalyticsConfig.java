package com.aram.backend.config;

import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryOptions;
import com.aram.backend.analytics.Pseudonymizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/** BigQuery client and pseudonym key for the export job; nothing here exists unless {@code ARAM_EXPORT_ENABLED=true}. */
@Configuration
@ConditionalOnProperty(prefix = "aram.export", name = "enabled", havingValue = "true")
public class AnalyticsConfig {

	@Bean
	public BigQuery bigQuery(AramProperties properties) {
		AramProperties.Export export = properties.getExport();
		String project = StringUtils.hasText(export.getProjectId()) ? export.getProjectId()
				: properties.getFirestore().getProjectId();
		if (!StringUtils.hasText(project)) {
			throw new IllegalStateException("Set aram.export.project-id (or GOOGLE_CLOUD_PROJECT) for the analytics export");
		}
		return BigQueryOptions.newBuilder().setProjectId(project).setLocation(export.getLocation())
				.setCredentials(GcpCredentials.forProject(project)).build().getService();
	}

	@Bean
	public Pseudonymizer pseudonymizer(AramProperties properties) {
		return new Pseudonymizer(properties.getExport().getHmacKey());
	}
}
