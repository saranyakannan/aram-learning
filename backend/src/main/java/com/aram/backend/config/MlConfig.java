package com.aram.backend.config;

import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/** BigQuery client for the recommendation job; nothing here exists unless {@code ARAM_ML_ENABLED=true}. */
@Configuration
@ConditionalOnProperty(prefix = "aram.ml", name = "enabled", havingValue = "true")
public class MlConfig {

	@Bean
	public BigQuery mlBigQuery(AramProperties properties) {
		AramProperties.Export export = properties.getExport();
		String project = StringUtils.hasText(export.getProjectId()) ? export.getProjectId()
				: properties.getFirestore().getProjectId();
		if (!StringUtils.hasText(project)) {
			throw new IllegalStateException("Set aram.export.project-id (or GOOGLE_CLOUD_PROJECT) for the recommendation job");
		}
		return BigQueryOptions.newBuilder().setProjectId(project).setLocation(export.getLocation())
				.setCredentials(GcpCredentials.forProject(project)).build().getService();
	}
}
