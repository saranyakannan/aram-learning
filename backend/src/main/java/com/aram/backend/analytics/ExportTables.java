package com.aram.backend.analytics;

import java.util.List;

import static com.aram.backend.analytics.Column.bool;
import static com.aram.backend.analytics.Column.date;
import static com.aram.backend.analytics.Column.decimal;
import static com.aram.backend.analytics.Column.integer;
import static com.aram.backend.analytics.Column.string;
import static com.aram.backend.analytics.Column.strings;
import static com.aram.backend.analytics.Column.timestamp;

/**
 * The analytics tables and their columns. This file is the privacy allowlist: a Firestore field that is not a column here
 * is never exported. There is deliberately no column for any name, email, uid, handle, birth month, free text typed by a
 * parent or a model's per-child explanation.
 */
public final class ExportTables {

	public static final String OPS_RUNS = "ops_export_runs";

	private ExportTables() {
	}

	public static List<ExportTable> all() {
		return List.of(
				new ExportTable("dim_categories", ExportTable.Mode.SNAPSHOT,
						List.of(string("category_id"), string("name"), string("description"), integer("sort_order"), bool("is_active")),
						List.of("category_id"), null, AnalyticsExtractors::categories),
				new ExportTable("dim_tags", ExportTable.Mode.SNAPSHOT,
						List.of(string("tag_id"), string("name"), string("normalized_name"), string("category_id"),
								integer("min_age_months"), integer("max_age_months"), bool("is_active")),
						List.of("tag_id"), null, AnalyticsExtractors::tags),
				new ExportTable("dim_content_sources", ExportTable.Mode.SNAPSHOT,
						List.of(string("source_id"), string("name"), string("normalized_name"), string("source_type"),
								string("platform"), string("canonical_url"), string("platform_source_id"), strings("tag_ids"),
								strings("origin_types"), string("catalog_status"), string("assessment_status"),
								string("latest_assessment_id"), integer("min_age_months"), integer("max_age_months"),
								date("created_date"), date("updated_date"), date("last_verified_date"), string("description")),
						List.of("source_id"), null, AnalyticsExtractors::sources),
				new ExportTable("bridge_content_tags", ExportTable.Mode.SNAPSHOT,
						List.of(string("target_type"), string("target_id"), string("tag_id"), string("association_source"),
								decimal("confidence"), date("valid_from"), date("valid_to")),
						List.of("target_type", "target_id", "tag_id"), null, AnalyticsExtractors::contentTags),
				new ExportTable("fact_content_assessments", ExportTable.Mode.INCREMENTAL,
						List.of(string("assessment_id"), string("target_type"), string("target_id"), string("assessor_type"),
								string("assessment_status"), integer("min_age_months"), integer("max_age_months"),
								strings("suggested_tag_ids"), strings("learning_goals"), strings("safety_findings"),
								string("evidence_level"), strings("limitations"), string("model_name"), string("model_version"),
								string("policy_version"), timestamp("assessed_at"), date("reviewed_date")),
						List.of("assessment_id"), "assessed_at", AnalyticsExtractors::assessments),
				new ExportTable("dim_parents", ExportTable.Mode.SNAPSHOT,
						List.of(string("parent_pseudonym"), string("account_status"), string("preferred_language"),
								bool("social_discovery_enabled"), date("created_month")),
						List.of("parent_pseudonym"), null, AnalyticsExtractors::parents),
				new ExportTable("dim_children", ExportTable.Mode.SNAPSHOT,
						List.of(string("child_pseudonym"), string("parent_pseudonym"), string("age_band"),
								string("preferred_language"), string("profile_status"), date("created_month")),
						List.of("child_pseudonym"), null, AnalyticsExtractors::children),
				new ExportTable("fact_parent_interests", ExportTable.Mode.SNAPSHOT,
						List.of(string("parent_pseudonym"), string("child_pseudonym"), string("tag_id"),
								string("preference_source"), decimal("weight"), date("selected_month")),
						List.of("child_pseudonym", "tag_id"), null, AnalyticsExtractors::interests),
				new ExportTable("fact_child_catalog_entries", ExportTable.Mode.SNAPSHOT,
						List.of(string("child_pseudonym"), string("parent_pseudonym"), string("source_id"),
								string("content_item_id"), string("status"), date("approved_date"), date("updated_date"),
								integer("restriction_count")),
						List.of("child_pseudonym", "source_id"), null, AnalyticsExtractors::catalogEntries));
	}

	/** The run log. Not an export table: written by the job itself, never from Firestore. */
	public static List<Column> opsColumns() {
		return List.of(string("run_id"), string("table_name"), string("mode"), timestamp("started_at"), timestamp("finished_at"),
				integer("rows_read"), integer("rows_loaded"), integer("rows_before"), integer("rows_after"),
				string("status"), integer("attempt"), string("error_class"), string("watermark"));
	}

	/** Tables that hold per-family rows and so carry {@code parent_pseudonym} (used by the purge). */
	public static List<ExportTable> familyTables() {
		return all().stream()
				.filter(table -> table.columns().stream().anyMatch(column -> column.name().equals("parent_pseudonym")))
				.toList();
	}
}
