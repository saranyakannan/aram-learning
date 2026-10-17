package com.aram.backend.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.aram.backend.catalog.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.aram.backend.analytics.FakeExportData.map;
import static com.aram.backend.analytics.FakeExportData.ts;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The privacy boundary. Firestore documents are stuffed with sentinel values for everything that must never reach BigQuery
 * (names, emails, uids, birth months, free text, URLs, and fields the export has never heard of). Every table is exported, all
 * rows are serialised, and none of the sentinels may appear anywhere.
 */
class AnalyticsPrivacyTest {

	private static final String UID = "CANARYUID0000000000000000001";
	private static final String CHILD_ID = UID + "-c1";
	private static final String PARENT_NAME = "CANARY Parent Name";
	private static final String HANDLE = "canary.handle";
	private static final String EMAIL = "canary.parent@example.com";
	private static final String CHILD_NAME = "CANARY Child Name";
	private static final String BIRTH = "2022-08";
	private static final String SUMMARY = "CANARY model explanation about the child";
	private static final String REVIEWER = "CANARY-reviewer-uid";
	private static final String THUMB = "https://canary.example.com/thumb.jpg";
	private static final String FEED = "https://canary.example.com/feed.xml";
	private static final String INPUT = "CANARY text the parent typed";
	private static final String EXTRA = "CANARY-unknown-field-value";

	private static final List<String> SENTINELS = List.of(UID, CHILD_ID, PARENT_NAME, HANDLE, EMAIL, CHILD_NAME, BIRTH, SUMMARY,
			REVIEWER, THUMB, FEED, INPUT, EXTRA, "CANARY");

	private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
	private FakeExportData data;
	private ExportTable.Context context;

	@BeforeEach
	void setUp() {
		data = new FakeExportData();
		data.put(Collections.PARENTS, UID, map("displayName", PARENT_NAME, "handle", HANDLE, "email", EMAIL, "preferredLanguage", "en",
				"accountStatus", "ACTIVE", "socialDiscoveryEnabled", false, "createdAt", ts("2026-10-14T13:47:59Z"), "updatedAt",
				ts("2026-10-14T13:47:59Z"), "extraField", EXTRA));
		data.put(Collections.CHILDREN, CHILD_ID, map("parentId", UID, "displayName", CHILD_NAME, "birthYearMonth", BIRTH,
				"preferredLanguage", "en", "profileStatus", "ACTIVE", "createdAt", ts("2026-10-14T13:47:59Z"), "extraField", EXTRA));
		data.put(Collections.PARENT_INTERESTS, CHILD_ID + "_math", map("parentId", UID, "childId", CHILD_ID, "tagId", "math",
				"preferenceSource", "PARENT_SELECTED", "weight", 1.0, "createdAt", ts("2026-10-14T13:47:59Z"), "extraField", EXTRA));
		data.put(Collections.CHILD_CATALOG_ENTRIES, CHILD_ID + "_youtube_UCabc", map("parentId", UID, "childId", CHILD_ID, "sourceId",
				"youtube_UCabc", "status", "APPROVED", "approvedBy", UID, "approvedAt", ts("2026-10-14T13:47:59Z"), "restrictions",
				List.of("CANARY free text restriction"), "createdAt", ts("2026-10-14T13:47:59Z"), "updatedAt", ts("2026-10-14T13:47:59Z"),
				"extraField", EXTRA));
		data.put(Collections.CONTENT_SOURCES, "youtube_UCabc", map("name", "Numberblocks", "normalizedName", "numberblocks", "sourceType",
				"YOUTUBE_CHANNEL", "platform", "YOUTUBE", "canonicalUrl", "https://www.youtube.com/@Numberblocks", "platformSourceId",
				"UCabc", "description", "Counting", "tagIds", List.of("math"), "originTypes", List.of("PREPOPULATED"), "catalogStatus",
				"ACTIVE", "assessmentStatus", "APPROVED_FOR_RECOMMENDATION", "latestAssessmentId", "a1", "minAgeMonths", 24L,
				"maxAgeMonths", 71L, "handle", "@Numberblocks", "thumbnailUrl", THUMB, "feedUrl", FEED, "subscriberCount", 9L, "createdAt",
				ts("2026-10-14T13:47:59Z"), "updatedAt", ts("2026-10-14T13:47:59Z"), "lastVerifiedAt", ts("2026-10-14T13:47:59Z"),
				"extraField", EXTRA));
		data.put(Collections.CONTENT_ASSESSMENTS, "a1", map("targetType", "CONTENT_SOURCE", "targetId", "youtube_UCabc", "assessorType",
				"LLM", "assessmentStatus", "ASSESSED", "minAgeMonths", 24L, "maxAgeMonths", 71L, "suggestedTagIds", List.of("math"),
				"learningGoals", List.of(), "safetyFindings", List.of(), "summary", SUMMARY, "evidenceLevel", "METADATA_ONLY",
				"limitations", List.of("Metadata only"), "modelName", "gemini", "modelVersion", "gemini", "policyVersion",
				"content-policy-v1", "forChildAgeMonths", 55L, "assessedAt", ts("2026-10-14T13:47:59Z"), "reviewedBy", REVIEWER,
				"reviewedAt", ts("2026-10-14T14:10:00Z"), "extraField", EXTRA));
		data.put(Collections.CATEGORIES, "math-counting", map("name", "Math & Counting", "description", "d", "sortOrder", 3L, "isActive",
				true, "extraField", EXTRA));
		data.put(Collections.TAGS, "math", map("name", "Math", "normalizedName", "math", "categoryId", "math-counting", "minAgeMonths", 24L,
				"maxAgeMonths", 96L, "isActive", true, "extraField", EXTRA));
		context = new ExportTable.Context(data, new Pseudonymizer("s".repeat(48)),
				Clock.fixed(Instant.parse("2026-10-14T12:00:00Z"), ZoneOffset.UTC), null);
	}

	private List<Map<String, Object>> exportEverything() {
		List<Map<String, Object>> rows = new ArrayList<>();
		for (ExportTable table : ExportTables.all()) {
			for (Map<String, Object> row : table.extractor().extract(context).rows()) {
				rows.add(table.seal(row));
			}
		}
		return rows;
	}

	@Test
	void noSentinelFromAnyDocumentAppearsInAnyExportedRow() throws Exception {
		List<Map<String, Object>> rows = exportEverything();
		assertThat(rows).as("every table produced rows, so the check is meaningful").hasSizeGreaterThan(8);

		String everything = json.writeValueAsString(rows).toLowerCase(Locale.ROOT);

		for (String sentinel : SENTINELS) {
			assertThat(everything).as("sentinel '" + sentinel + "' must not be exported").doesNotContain(sentinel.toLowerCase(Locale.ROOT));
		}
	}

	@Test
	void everyTableProducesRowsFromTheSampleSoTheCanaryTestCannotPassEmpty() {
		for (ExportTable table : ExportTables.all()) {
			assertThat(table.extractor().extract(context).rows()).as(table.name()).isNotEmpty();
		}
	}

	@Test
	void familyRowsUseKeyedPseudonymsInsteadOfIds() {
		Pseudonymizer pseudonyms = context.pseudonyms();

		var parents = ExportTables.all().stream().filter(t -> t.name().equals("dim_parents")).findFirst().orElseThrow().extractor().extract(context).rows();
		var children = ExportTables.all().stream().filter(t -> t.name().equals("dim_children")).findFirst().orElseThrow().extractor().extract(context).rows();

		assertThat(parents.get(0).get("parent_pseudonym")).isEqualTo(pseudonyms.parent(UID));
		assertThat(children.get(0).get("child_pseudonym")).isEqualTo(pseudonyms.child(CHILD_ID));
		assertThat(children.get(0).get("parent_pseudonym")).isEqualTo(pseudonyms.parent(UID));
		assertThat(children.get(0).get("age_band")).isEqualTo("4-5y");
		assertThat(children.get(0)).doesNotContainKeys("birth_year_month", "display_name", "age", "date_of_birth");
	}

	@Test
	void aTableDefinitionCannotContainAnyPersonalColumn() {
		Set<String> forbiddenFragments = Set.of("display_name", "displayname", "handle", "email", "uid", "birth", "dob", "date_of_birth",
				"input_text", "input_url", "summary", "for_child", "reviewed_by", "approved_by", "thumbnail", "feed_url", "subscriber",
				"password", "phone", "address", "ip_");
		List<Column> all = new ArrayList<>(ExportTables.opsColumns());
		ExportTables.all().forEach(table -> all.addAll(table.allColumns()));

		for (Column column : all) {
			for (String fragment : forbiddenFragments) {
				assertThat(column.name().toLowerCase(Locale.ROOT)).as("column " + column.name()).doesNotContain(fragment);
			}
		}
	}

	@Test
	void familyTablesHaveNoNameLikeColumnAtAll() {
		for (ExportTable table : ExportTables.familyTables()) {
			Set<String> names = table.columns().stream().map(Column::name).collect(Collectors.toSet());
			assertThat(names).as(table.name()).noneMatch(name -> name.contains("name") && !name.equals("preferred_language"));
		}
	}

	@Test
	void onlyCatalogTablesCarryNamesAndTheyAreAllPublicCatalogData() {
		Set<String> withNames = ExportTables.all().stream().filter(t -> t.columns().stream().anyMatch(c -> c.name().contains("name")))
				.map(ExportTable::name).collect(Collectors.toSet());

		assertThat(withNames).containsExactlyInAnyOrder("dim_categories", "dim_tags", "dim_content_sources", "fact_content_assessments");
	}

	@Test
	void theExportedAssessmentCarriesNoTextAboutTheChildAndKeepsItsEvidenceAndVersions() {
		var rows = ExportTables.all().stream().filter(t -> t.name().equals("fact_content_assessments")).findFirst().orElseThrow()
				.extractor().extract(context).rows();

		assertThat(rows.get(0)).containsEntry("evidence_level", "METADATA_ONLY").containsEntry("policy_version", "content-policy-v1")
				.containsEntry("model_name", "gemini").containsEntry("assessed_at", Instant.parse("2026-10-14T13:00:00Z"))
				.doesNotContainKeys("summary", "for_child_age_months", "reviewed_by");
	}

	@Test
	void anExtraFieldAddedToFirestoreLaterNeverReachesBigQueryUntilAColumnIsAddedOnPurpose() throws Exception {
		data.collections.values().forEach(docs -> docs.forEach(doc -> doc.data().put("brandNewSensitiveField", "CANARY-NEW")));

		assertThat(json.writeValueAsString(exportEverything())).doesNotContain("CANARY-NEW");
	}
	@Test
	void theSourceDescriptionIsPublicCatalogTextAndNeverTheTextAParentTyped() throws Exception {
		var rows = ExportTables.all().stream().filter(t -> t.name().equals("dim_content_sources")).findFirst().orElseThrow()
				.extractor().extract(context).rows();

		assertThat(rows).isNotEmpty();
		assertThat(rows.get(0)).containsEntry("description", "Counting");
		assertThat(json.writeValueAsString(rows)).doesNotContain(INPUT);
	}

	@Test
	void noExportTableEverReadsTheAgentConversations() {
		List<String> read = new java.util.ArrayList<>();
		ExportData recording = new ExportData() {
			@Override
			public List<com.aram.backend.catalog.FirestoreOps.Doc> all(String collection) {
				read.add(collection);
				return data.all(collection);
			}

			@Override
			public List<com.aram.backend.catalog.FirestoreOps.Doc> atLeast(String collection, String field, java.time.Instant atLeast) {
				read.add(collection);
				return data.atLeast(collection, field, atLeast);
			}
		};
		ExportTable.Context recordingContext = new ExportTable.Context(recording, context.pseudonyms(), context.clock(), null);

		ExportTables.all().forEach(table -> table.extractor().extract(recordingContext));

		assertThat(read).isNotEmpty().doesNotContain("adk-session").noneMatch(name -> name.toLowerCase().contains("session"));
	}
}
