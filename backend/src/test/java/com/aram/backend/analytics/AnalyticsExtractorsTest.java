package com.aram.backend.analytics;

import com.aram.backend.catalog.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static com.aram.backend.analytics.FakeExportData.map;
import static com.aram.backend.analytics.FakeExportData.ts;
import static org.assertj.core.api.Assertions.assertThat;

class AnalyticsExtractorsTest {

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-14T12:00:00Z"), ZoneOffset.UTC);

	private FakeExportData data;

	@BeforeEach
	void setUp() {
		data = new FakeExportData();
	}

	private ExportTable.Extraction run(String table, String watermark) {
		ExportTable definition = ExportTables.all().stream().filter(t -> t.name().equals(table)).findFirst().orElseThrow();
		return definition.extractor().extract(new ExportTable.Context(data, new Pseudonymizer("s".repeat(40)), CLOCK, watermark));
	}

	private static Map<String, Object> assessment(String assessedAt) {
		return map("targetType", "CONTENT_SOURCE", "targetId", "youtube_UCabc", "assessorType", "LLM", "assessmentStatus", "ASSESSED",
				"minAgeMonths", 24L, "maxAgeMonths", 59L, "assessedAt", ts(assessedAt));
	}

	@Test
	void assessmentsAreReadFromTheWatermarkInclusiveAndTheWatermarkIsTheNewestRawTime() {
		data.put(Collections.CONTENT_ASSESSMENTS, "old", assessment("2026-10-10T08:30:00Z"));
		data.put(Collections.CONTENT_ASSESSMENTS, "edge", assessment("2026-10-12T09:00:00Z"));
		data.put(Collections.CONTENT_ASSESSMENTS, "new", assessment("2026-10-13T17:45:30Z"));

		var first = run("fact_content_assessments", null);
		var next = run("fact_content_assessments", "2026-10-12T09:00:00Z");

		assertThat(first.rows()).extracting(r -> r.get("assessment_id")).containsExactlyInAnyOrder("old", "edge", "new");
		assertThat(first.watermark()).isEqualTo("2026-10-13T17:45:30Z");
		assertThat(next.rows()).extracting(r -> r.get("assessment_id")).containsExactlyInAnyOrder("edge", "new");
		assertThat(run("fact_content_assessments", "2026-10-13T17:45:30Z").rows()).extracting(r -> r.get("assessment_id")).containsExactly("new");
	}

	@Test
	void withNothingNewTheWatermarkStaysWhereItWas() {
		data.put(Collections.CONTENT_ASSESSMENTS, "old", assessment("2026-10-10T08:30:00Z"));

		var result = run("fact_content_assessments", "2026-10-11T00:00:00Z");

		assertThat(result.rows()).isEmpty();
		assertThat(result.watermark()).isEqualTo("2026-10-11T00:00:00Z");
	}

	@Test
	void tagAssociationsSayWhereTheTagCameFrom() {
		data.put(Collections.CONTENT_ASSESSMENTS, "human", map("assessorType", "HUMAN_REVIEW", "assessedAt", ts("2026-10-10T08:30:00Z")));
		data.put(Collections.CONTENT_ASSESSMENTS, "llm", map("assessorType", "LLM", "assessedAt", ts("2026-10-10T08:30:00Z")));
		data.put(Collections.CONTENT_SOURCES, "youtube_UCseed", map("tagIds", List.of("math", "music"), "originTypes", List.of("PREPOPULATED"),
				"latestAssessmentId", "llm", "createdAt", ts("2026-09-01T10:00:00Z")));
		data.put(Collections.CONTENT_SOURCES, "youtube_UCsub", map("tagIds", List.of("nature"), "originTypes", List.of("PARENT_SUBMITTED"),
				"latestAssessmentId", "llm", "createdAt", ts("2026-10-01T10:00:00Z")));
		data.put(Collections.CONTENT_SOURCES, "youtube_UCrev", map("tagIds", List.of("art"), "originTypes", List.of("PARENT_SUBMITTED"),
				"latestAssessmentId", "human", "createdAt", ts("2026-10-02T10:00:00Z")));

		var rows = run("bridge_content_tags", null).rows();

		assertThat(rows).hasSize(4);
		assertThat(rows).filteredOn(r -> r.get("target_id").equals("youtube_UCseed")).allMatch(r -> r.get("association_source").equals("CURATED"));
		assertThat(rows).filteredOn(r -> r.get("target_id").equals("youtube_UCsub")).allMatch(r -> r.get("association_source").equals("LLM_PROPOSED"));
		assertThat(rows).filteredOn(r -> r.get("target_id").equals("youtube_UCrev")).allMatch(r -> r.get("association_source").equals("HUMAN_REVIEWED"));
		assertThat(rows.get(0).get("valid_from")).isEqualTo(LocalDate.of(2026, 9, 1));
		assertThat(rows.get(0).get("confidence")).isNull();
	}

	@Test
	void aChildsAgeBandIsComputedFromTheBirthMonthAndBadDataBecomesUnknown() {
		data.put(Collections.CHILDREN, "p1-c1", map("parentId", "p1", "birthYearMonth", "2022-08", "createdAt", ts("2026-10-14T01:00:00Z")));
		data.put(Collections.CHILDREN, "p2-c1", map("parentId", "p2", "birthYearMonth", "garbage", "createdAt", ts("2026-10-14T01:00:00Z")));
		data.put(Collections.CHILDREN, "p3-c1", map("parentId", "p3", "birthYearMonth", "2026-10", "createdAt", ts("2026-10-14T01:00:00Z")));

		var rows = run("dim_children", null).rows();

		assertThat(rows).extracting(r -> r.get("age_band")).containsExactly("4-5y", "unknown", "<1y");
		assertThat(rows.get(0).get("created_month")).isEqualTo(LocalDate.of(2026, 10, 1));
	}

	@Test
	void familyDatesAreCoarseAndRestrictionsAreOnlyCounted() {
		data.put(Collections.CHILD_CATALOG_ENTRIES, "c1_s1", map("parentId", "p1", "childId", "c1", "sourceId", "youtube_UCabc", "status",
				"REVOKED", "approvedAt", ts("2026-10-12T22:15:00Z"), "updatedAt", ts("2026-10-14T03:00:00Z"), "restrictions",
				List.of("no scary stuff", "after 6pm")));

		var row = run("fact_child_catalog_entries", null).rows().get(0);

		assertThat(row).containsEntry("status", "REVOKED").containsEntry("restriction_count", 2L)
				.containsEntry("approved_date", LocalDate.of(2026, 10, 12)).containsEntry("updated_date", LocalDate.of(2026, 10, 14));
		assertThat(row.values()).doesNotContain("no scary stuff");
	}

	@Test
	void interestsKeepTheTagAndWeightButOnlyTheMonthTheyWereSelected() {
		data.put(Collections.PARENT_INTERESTS, "c1_math", map("parentId", "p1", "childId", "c1", "tagId", "math", "preferenceSource",
				"PARENT_SELECTED", "weight", 1L, "createdAt", ts("2026-10-14T13:47:59Z")));

		var row = run("fact_parent_interests", null).rows().get(0);

		assertThat(row).containsEntry("tag_id", "math").containsEntry("weight", 1.0).containsEntry("selected_month", LocalDate.of(2026, 10, 1));
	}
}
