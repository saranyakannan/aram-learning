package com.aram.backend.analytics;

import com.google.cloud.bigquery.Acl;
import com.google.cloud.bigquery.Field;
import com.google.cloud.bigquery.TableId;
import com.google.cloud.bigquery.StandardSQLTypeName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExportTablesTest {

	@Test
	void everyTableIsWellFormed() {
		Set<String> names = new HashSet<>();
		for (ExportTable table : ExportTables.all()) {
			assertThat(names.add(table.name())).as("unique name " + table.name()).isTrue();
			Set<String> columns = table.columns().stream().map(Column::name).collect(Collectors.toSet());
			assertThat(columns).as(table.name() + " has unique columns").hasSize(table.columns().size());
			assertThat(columns).as(table.name() + " keys exist").containsAll(table.keyColumns());
			assertThat(table.keyColumns()).as(table.name() + " has a key").isNotEmpty();
			assertThat(columns).as("row_hash is added, not declared").doesNotContain(ExportTable.ROW_HASH);
			assertThat(table.allColumns().get(table.allColumns().size() - 1).name()).isEqualTo(ExportTable.ROW_HASH);
			if (table.partitionColumn() != null) {
				Column partition = table.columns().stream().filter(c -> c.name().equals(table.partitionColumn())).findFirst().orElseThrow();
				assertThat(partition.type()).isIn(Column.Type.DATE, Column.Type.TIMESTAMP);
			}
		}
	}

	@Test
	void onlyTheAssessmentHistoryIsIncrementalAndKeepsAllItsHistory() {
		List<String> incremental = ExportTables.all().stream().filter(t -> t.mode() == ExportTable.Mode.INCREMENTAL).map(ExportTable::name).toList();

		assertThat(incremental).containsExactly("fact_content_assessments");
		assertThat(ExportTables.all().stream().map(ExportTable::name)).contains("dim_parents", "dim_children", "fact_parent_interests",
				"fact_child_catalog_entries", "dim_content_sources", "bridge_content_tags", "dim_tags", "dim_categories");
	}

	@Test
	void theFamilyTablesAreExactlyThoseCarryingAParentPseudonym() {
		assertThat(ExportTables.familyTables().stream().map(ExportTable::name)).containsExactlyInAnyOrder("dim_parents", "dim_children",
				"fact_parent_interests", "fact_child_catalog_entries");
	}

	@Test
	void sealRefusesAColumnThatIsNotOnTheAllowlistAndAddsAStableHash() {
		ExportTable categories = ExportTables.all().get(0);
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("category_id", "a");
		row.put("name", "A");

		Map<String, Object> sealed = categories.seal(row);

		assertThat(sealed).containsKey(ExportTable.ROW_HASH);
		assertThat(categories.seal(new LinkedHashMap<>(row)).get(ExportTable.ROW_HASH)).isEqualTo(sealed.get(ExportTable.ROW_HASH));
		row.put("name", "B");
		assertThat(categories.seal(row).get(ExportTable.ROW_HASH)).isNotEqualTo(sealed.get(ExportTable.ROW_HASH));
		row.put("display_name", "Someone");
		assertThatThrownBy(() -> categories.seal(row)).isInstanceOf(IllegalStateException.class).hasMessageContaining("allowlist");
	}

	@Test
	void theSchemaSentToBigQueryMatchesTheDefinitionIncludingArrays() {
		var schema = BigQueryJobSink.schema(ExportTables.all().stream().filter(t -> t.name().equals("dim_content_sources")).findFirst().orElseThrow().allColumns());

		Field tagIds = schema.getFields().get("tag_ids");
		assertThat(tagIds.getMode()).isEqualTo(Field.Mode.REPEATED);
		assertThat(tagIds.getType().getStandardType()).isEqualTo(StandardSQLTypeName.STRING);
		assertThat(schema.getFields().get("created_date").getType().getStandardType()).isEqualTo(StandardSQLTypeName.DATE);
		assertThat(schema.getFields().get("min_age_months").getType().getStandardType()).isEqualTo(StandardSQLTypeName.INT64);
		assertThat(schema.getFields().get(ExportTable.ROW_HASH).getMode()).isEqualTo(Field.Mode.NULLABLE);
	}

	@Test
	void jsonRowsOmitNullsAndWriteDatesAndInstantsAsText() {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("a", LocalDate.of(2026, 10, 1));
		row.put("b", Instant.parse("2026-10-14T13:00:00Z"));
		row.put("c", null);
		row.put("d", List.of("x"));
		row.put("e", 5L);

		assertThat(BigQueryJobSink.jsonRow(row)).containsOnlyKeys("a", "b", "d", "e")
				.containsEntry("a", "2026-10-01").containsEntry("b", "2026-10-14T13:00:00Z");
	}

	@Test
	void viewsAlreadyAuthorisedOnTheDatasetAreNotAddedAgain() {
		TableId v1 = TableId.of("p", "views", "v_one");
		TableId v2 = TableId.of("p", "views", "v_two");
		List<Acl> afterFirstRun = BigQueryJobSink.withViewAuthorizations(List.of(Acl.of(new Acl.User("analyst@example.com"), Acl.Role.READER)), List.of(v1, v2));

		assertThat(afterFirstRun).hasSize(3);
		assertThat(BigQueryJobSink.withViewAuthorizations(afterFirstRun, List.of(v1, v2))).as("a second run changes nothing").hasSize(3);
		assertThat(BigQueryJobSink.withViewAuthorizations(afterFirstRun, List.of(v1, TableId.of("p", "views", "v_three")))).hasSize(4);
		assertThat(BigQueryJobSink.withViewAuthorizations(List.of(), List.of())).isEmpty();
	}

	@Test
	void theRunLogHasOnlyCountsStatusesAndNoRowContent() {
		assertThat(ExportTables.opsColumns().stream().map(Column::name)).containsExactly("run_id", "table_name", "mode", "started_at",
				"finished_at", "rows_read", "rows_loaded", "rows_before", "rows_after", "status", "attempt", "error_class", "watermark");
	}
	private static List<Field> liveFields(List<Column> columns) {
		return BigQueryJobSink.schema(columns).getFields();
	}

	@Test
	void descriptionIsPublicCatalogTextCutToFiveHundredCharacters() {
		assertThat(AnalyticsExtractors.publicText(null)).isNull();
		assertThat(AnalyticsExtractors.publicText("   ")).isNull();
		assertThat(AnalyticsExtractors.publicText("  Counting songs  ")).isEqualTo("Counting songs");
		assertThat(AnalyticsExtractors.publicText("x".repeat(900))).hasSize(500);
	}

	@Test
	void aColumnAddedLaterSitsAfterTheRowHashAndIsNotDrift() {
		ExportTable sources = ExportTables.all().stream().filter(t -> t.name().equals("dim_content_sources")).findFirst().orElseThrow();
		List<Column> expected = sources.allColumns();
		List<Column> reordered = new java.util.ArrayList<>(expected);
		Column description = reordered.stream().filter(c -> c.name().equals("description")).findFirst().orElseThrow();
		reordered.remove(description);
		reordered.add(description);

		assertThat(BigQueryJobSink.describeDrift("dim_content_sources", liveFields(reordered), expected)).isEmpty();
	}

	@Test
	void missingUnexpectedAndRetypedColumnsAreDrift() {
		List<Column> expected = List.of(Column.string("a"), Column.integer("b"));

		assertThat(BigQueryJobSink.describeDrift("t", liveFields(List.of(Column.string("a"))), expected)).hasValueSatisfying(
				message -> assertThat(message).contains("missing column 'b'"));
		assertThat(BigQueryJobSink.describeDrift("t", liveFields(List.of(Column.string("a"), Column.integer("b"), Column.string("c"))),
				expected)).hasValueSatisfying(message -> assertThat(message).contains("unexpected column 'c'"));
		assertThat(BigQueryJobSink.describeDrift("t", liveFields(List.of(Column.string("a"), Column.string("b"))), expected))
				.hasValueSatisfying(message -> assertThat(message).contains("different type"));
		assertThat(BigQueryJobSink.describeDrift("t", liveFields(List.of(Column.string("a"), Column.strings("b"))), expected))
				.isPresent();
	}

	@Test
	void theMigrationOnlyAddsColumnsTheLiveTableLacks() {
		List<Column> expected = List.of(Column.string("a"), Column.integer("b"), Column.string("description"));

		assertThat(BigQueryJobSink.missingColumns(liveFields(List.of(Column.string("a"), Column.integer("b"))), expected))
				.extracting(Column::name).containsExactly("description");
		assertThat(BigQueryJobSink.missingColumns(liveFields(expected), expected)).isEmpty();
	}
}
