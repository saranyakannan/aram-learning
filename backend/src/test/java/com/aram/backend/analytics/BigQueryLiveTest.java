package com.aram.backend.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryOptions;
import com.google.cloud.bigquery.DatasetId;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.TableId;
import com.google.auth.oauth2.GoogleCredentials;
import com.aram.backend.catalog.Collections;
import com.aram.backend.config.AramProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static com.aram.backend.analytics.FakeExportData.map;
import static com.aram.backend.analytics.FakeExportData.ts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs the real export against real BigQuery, but only in a throwaway dataset it creates and deletes. Fake Firestore data.
 * Skipped unless run as {@code ARAM_LIVE_BIGQUERY=true ARAM_LIVE_PROJECT=<project> ./gradlew test --tests '*BigQueryLiveTest' --info}.
 */
@EnabledIfEnvironmentVariable(named = "ARAM_LIVE_BIGQUERY", matches = "true")
class BigQueryLiveTest {

	private static final String PARENT = "live-parent-uid-0000000000001";
	private final String project = System.getenv("ARAM_LIVE_PROJECT");
	private final String dataset = "aram_export_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
	private final String viewsDataset = dataset + "_views";

	private BigQuery bigQuery;
	private BigQueryJobSink sink;
	private FakeExportData data;
	private ExportService service;

	@BeforeEach
	void setUp() throws Exception {
		bigQuery = BigQueryOptions.newBuilder().setProjectId(project).setLocation("us-central1")
				.setCredentials(GoogleCredentials.getApplicationDefault().createWithQuotaProject(project)).build().getService();
		AramProperties properties = new AramProperties();
		properties.getExport().setDataset(dataset);
		properties.getExport().setViewsDataset(viewsDataset);
		sink = new BigQueryJobSink(bigQuery, new ObjectMapper(), properties);
		data = new FakeExportData();
		data.put(Collections.CATEGORIES, "math-counting", map("name", "Math", "sortOrder", 1L, "isActive", true));
		data.put(Collections.PARENTS, PARENT, map("displayName", "LIVE CANARY NAME", "accountStatus", "ACTIVE", "createdAt", ts("2026-10-01T00:00:00Z")));
		data.put(Collections.CONTENT_ASSESSMENTS, "a1", map("targetType", "CONTENT_SOURCE", "targetId", "s1", "assessorType", "LLM",
				"assessmentStatus", "ASSESSED", "assessedAt", ts("2026-10-10T08:30:00Z"), "summary", "LIVE CANARY SUMMARY"));
		service = new ExportService(data, new Pseudonymizer("live-test-key-".repeat(4)), sink,
				Clock.fixed(Instant.parse("2026-10-14T12:00:00Z"), ZoneOffset.UTC), 10, Thread::sleep, () -> UUID.randomUUID().toString(),
				ExportTables.all());
	}

	@AfterEach
	void tearDown() {
		for (String name : List.of(dataset, viewsDataset)) {
			bigQuery.delete(DatasetId.of(project, name), BigQuery.DatasetDeleteOption.deleteContents());
		}
	}

	private long count(String table) throws Exception {
		return bigQuery.query(QueryJobConfiguration.of("SELECT COUNT(*) AS n FROM `" + project + "." + dataset + "." + table + "`"))
				.iterateAll().iterator().next().get("n").getLongValue();
	}

	@Test
	void theWholeExportWorksIsIdempotentPropagatesDeletesAndPurges() throws Exception {
		var first = service.run(ExportService.Options.defaults());
		assertThat(first).allMatch(r -> r.status().equals("SUCCESS"));
		assertThat(count("dim_categories")).isEqualTo(1);
		assertThat(count("dim_parents")).isEqualTo(1);
		assertThat(count("fact_content_assessments")).isEqualTo(1);

		service.run(ExportService.Options.defaults());
		assertThat(count("dim_parents")).isEqualTo(1);
		assertThat(count("fact_content_assessments")).isEqualTo(1);
		assertThat(sink.lastWatermark("fact_content_assessments")).contains("2026-10-10T08:30:00Z");

		var scan = bigQuery.query(QueryJobConfiguration.of("SELECT COUNT(*) AS n FROM `" + project + "." + dataset
				+ ".dim_parents` AS p WHERE TO_JSON_STRING(p) LIKE '%CANARY%'")).iterateAll().iterator().next().get("n").getLongValue();
		assertThat(scan).isZero();

		data.collections.get(Collections.PARENTS).clear();
		service.run(ExportService.Options.defaults());
		assertThat(count("dim_parents")).as("a deletion in Firestore propagates").isZero();

		data.put(Collections.PARENTS, PARENT, map("accountStatus", "ACTIVE", "createdAt", ts("2026-10-01T00:00:00Z")));
		service.run(ExportService.Options.defaults());
		assertThat(count("dim_parents")).isEqualTo(1);
		assertThat(service.purgeParent(PARENT).get("dim_parents")).isEqualTo(1L);
		assertThat(count("dim_parents")).isZero();
	}

	@Test
	void aTableThatDriftedFromTheDefinitionIsDetectedBeforeAnythingIsWritten() throws Exception {
		service.initialize();
		bigQuery.query(QueryJobConfiguration.of("ALTER TABLE `" + project + "." + dataset + ".dim_tags` ADD COLUMN surprise STRING"));
		ExportTable tags = ExportTables.all().stream().filter(t -> t.name().equals("dim_tags")).findFirst().orElseThrow();

		assertThatThrownBy(() -> sink.validateSchema(tags)).isInstanceOf(SchemaDriftException.class);
		assertThat(bigQuery.getTable(TableId.of(dataset, "dim_tags"))).isNotNull();
	}
}
