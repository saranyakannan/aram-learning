package com.aram.backend.analytics;

import com.aram.backend.catalog.ChildCatalogEntry;
import com.aram.backend.catalog.ChildProfile;
import com.aram.backend.catalog.ContentAssessment;
import com.aram.backend.catalog.ContentSource;
import com.aram.backend.catalog.Collections;
import com.aram.backend.catalog.FirestoreOps;
import com.aram.backend.catalog.ParentAccount;
import com.aram.backend.catalog.Statuses;

import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.aram.backend.catalog.FirestoreOps.bool;
import static com.aram.backend.catalog.FirestoreOps.integer;
import static com.aram.backend.catalog.FirestoreOps.string;

/**
 * Turns Firestore documents into analytics rows. Every row is built column by column from named fields, never by copying a
 * document, so a new Firestore field (or a sensitive one) cannot reach BigQuery unless it is added here on purpose.
 */
public final class AnalyticsExtractors {

	private AnalyticsExtractors() {
	}

	private static ExportTable.Extraction all(List<Map<String, Object>> rows) {
		return new ExportTable.Extraction(rows, null);
	}

	static ExportTable.Extraction categories(ExportTable.Context ctx) {
		List<Map<String, Object>> rows = new ArrayList<>();
		for (FirestoreOps.Doc doc : ctx.data().all(Collections.CATEGORIES)) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("category_id", doc.id());
			row.put("name", string(doc.data(), "name"));
			row.put("description", string(doc.data(), "description"));
			row.put("sort_order", longOrNull(integer(doc.data(), "sortOrder")));
			row.put("is_active", bool(doc.data(), "isActive"));
			rows.add(row);
		}
		return all(rows);
	}

	static ExportTable.Extraction tags(ExportTable.Context ctx) {
		List<Map<String, Object>> rows = new ArrayList<>();
		for (FirestoreOps.Doc doc : ctx.data().all(Collections.TAGS)) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("tag_id", doc.id());
			row.put("name", string(doc.data(), "name"));
			row.put("normalized_name", string(doc.data(), "normalizedName"));
			row.put("category_id", string(doc.data(), "categoryId"));
			row.put("min_age_months", longOrNull(integer(doc.data(), "minAgeMonths")));
			row.put("max_age_months", longOrNull(integer(doc.data(), "maxAgeMonths")));
			row.put("is_active", bool(doc.data(), "isActive"));
			rows.add(row);
		}
		return all(rows);
	}

	/** Longest public catalog description exported (ADR 0009). */
	static final int DESCRIPTION_LIMIT = 500;

	/** Public catalog text only, cut to {@link #DESCRIPTION_LIMIT} characters; blank becomes null. */
	static String publicText(String text) {
		if (text == null || text.isBlank()) {
			return null;
		}
		String trimmed = text.strip();
		return trimmed.length() <= DESCRIPTION_LIMIT ? trimmed : trimmed.substring(0, DESCRIPTION_LIMIT);
	}

	static ExportTable.Extraction sources(ExportTable.Context ctx) {
		List<Map<String, Object>> rows = new ArrayList<>();
		for (FirestoreOps.Doc doc : ctx.data().all(Collections.CONTENT_SOURCES)) {
			ContentSource source = ContentSource.fromMap(doc.id(), doc.data());
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("source_id", source.id());
			row.put("name", source.name());
			row.put("normalized_name", source.normalizedName());
			row.put("source_type", source.sourceType());
			row.put("platform", source.platform());
			row.put("canonical_url", source.canonicalUrl());
			row.put("platform_source_id", source.platformSourceId());
			row.put("tag_ids", source.tagIds());
			row.put("origin_types", source.originTypes());
			row.put("catalog_status", source.catalogStatus().name());
			row.put("assessment_status", source.assessmentStatus().name());
			row.put("latest_assessment_id", source.latestAssessmentId());
			row.put("min_age_months", longOrNull(source.minAgeMonths()));
			row.put("max_age_months", longOrNull(source.maxAgeMonths()));
			row.put("created_date", TimePrecision.day(source.createdAt()));
			row.put("updated_date", TimePrecision.day(source.updatedAt()));
			row.put("last_verified_date", TimePrecision.day(source.lastVerifiedAt()));
			row.put("description", publicText(source.description()));
			rows.add(row);
		}
		return all(rows);
	}

	/** One row per source and tag. Seeded sources are CURATED; parent-submitted ones are LLM_PROPOSED until a human reviews them. */
	static ExportTable.Extraction contentTags(ExportTable.Context ctx) {
		Map<String, Statuses.Assessor> assessors = new HashMap<>();
		for (FirestoreOps.Doc doc : ctx.data().all(Collections.CONTENT_ASSESSMENTS)) {
			assessors.put(doc.id(), ContentAssessment.fromMap(doc.id(), doc.data()).assessorType());
		}
		List<Map<String, Object>> rows = new ArrayList<>();
		for (FirestoreOps.Doc doc : ctx.data().all(Collections.CONTENT_SOURCES)) {
			ContentSource source = ContentSource.fromMap(doc.id(), doc.data());
			String association;
			if (source.originTypes().contains(ContentSource.PREPOPULATED)) {
				association = "CURATED";
			} else if (assessors.get(source.latestAssessmentId()) == Statuses.Assessor.HUMAN_REVIEW) {
				association = "HUMAN_REVIEWED";
			} else {
				association = "LLM_PROPOSED";
			}
			for (String tagId : source.tagIds()) {
				Map<String, Object> row = new LinkedHashMap<>();
				row.put("target_type", ContentAssessment.CONTENT_SOURCE);
				row.put("target_id", source.id());
				row.put("tag_id", tagId);
				row.put("association_source", association);
				row.put("confidence", null);
				row.put("valid_from", TimePrecision.day(source.createdAt()));
				row.put("valid_to", null);
				rows.add(row);
			}
		}
		return all(rows);
	}

	/** Incremental: only assessments at or after the watermark; the watermark is the newest raw assessment time seen. */
	static ExportTable.Extraction assessments(ExportTable.Context ctx) {
		Instant since = ctx.previousWatermark() == null ? Instant.EPOCH : Instant.parse(ctx.previousWatermark());
		List<Map<String, Object>> rows = new ArrayList<>();
		Instant newest = since;
		for (FirestoreOps.Doc doc : ctx.data().atLeast(Collections.CONTENT_ASSESSMENTS, "assessedAt", since)) {
			ContentAssessment a = ContentAssessment.fromMap(doc.id(), doc.data());
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("assessment_id", a.id());
			row.put("target_type", a.targetType());
			row.put("target_id", a.targetId());
			row.put("assessor_type", a.assessorType().name());
			row.put("assessment_status", a.assessmentStatus().name());
			row.put("min_age_months", longOrNull(a.minAgeMonths()));
			row.put("max_age_months", longOrNull(a.maxAgeMonths()));
			row.put("suggested_tag_ids", a.suggestedTagIds());
			row.put("learning_goals", a.learningGoals());
			row.put("safety_findings", a.safetyFindings());
			row.put("evidence_level", a.evidenceLevel());
			row.put("limitations", a.limitations());
			row.put("model_name", a.modelName());
			row.put("model_version", a.modelVersion());
			row.put("policy_version", a.policyVersion());
			row.put("assessed_at", TimePrecision.hour(a.assessedAt()));
			row.put("reviewed_date", TimePrecision.day(a.reviewedAt()));
			rows.add(row);
			if (a.assessedAt() != null && a.assessedAt().isAfter(newest)) {
				newest = a.assessedAt();
			}
		}
		return new ExportTable.Extraction(rows, newest.toString());
	}

	static ExportTable.Extraction parents(ExportTable.Context ctx) {
		List<Map<String, Object>> rows = new ArrayList<>();
		for (FirestoreOps.Doc doc : ctx.data().all(Collections.PARENTS)) {
			ParentAccount parent = ParentAccount.fromMap(doc.id(), doc.data());
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("parent_pseudonym", ctx.pseudonyms().parent(parent.id()));
			row.put("account_status", parent.accountStatus());
			row.put("preferred_language", parent.preferredLanguage());
			row.put("social_discovery_enabled", parent.socialDiscoveryEnabled());
			row.put("created_month", TimePrecision.month(parent.createdAt()));
			rows.add(row);
		}
		return all(rows);
	}

	/** The exact birth month is turned into an age band here and then dropped. */
	static ExportTable.Extraction children(ExportTable.Context ctx) {
		List<Map<String, Object>> rows = new ArrayList<>();
		for (FirestoreOps.Doc doc : ctx.data().all(Collections.CHILDREN)) {
			ChildProfile child = ChildProfile.fromMap(doc.id(), doc.data());
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("child_pseudonym", ctx.pseudonyms().child(child.id()));
			row.put("parent_pseudonym", ctx.pseudonyms().parent(child.parentId()));
			row.put("age_band", ageBand(child.birthYearMonth(), ctx));
			row.put("preferred_language", child.preferredLanguage());
			row.put("profile_status", child.profileStatus());
			row.put("created_month", TimePrecision.month(child.createdAt()));
			rows.add(row);
		}
		return all(rows);
	}

	static ExportTable.Extraction interests(ExportTable.Context ctx) {
		List<Map<String, Object>> rows = new ArrayList<>();
		for (FirestoreOps.Doc doc : ctx.data().all(Collections.PARENT_INTERESTS)) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("parent_pseudonym", ctx.pseudonyms().parent(string(doc.data(), "parentId")));
			row.put("child_pseudonym", ctx.pseudonyms().child(string(doc.data(), "childId")));
			row.put("tag_id", string(doc.data(), "tagId"));
			row.put("preference_source", string(doc.data(), "preferenceSource"));
			row.put("weight", doc.data().get("weight") instanceof Number number ? number.doubleValue() : null);
			row.put("selected_month", TimePrecision.month(FirestoreOps.instant(doc.data().get("createdAt"))));
			rows.add(row);
		}
		return all(rows);
	}

	static ExportTable.Extraction catalogEntries(ExportTable.Context ctx) {
		List<Map<String, Object>> rows = new ArrayList<>();
		for (FirestoreOps.Doc doc : ctx.data().all(Collections.CHILD_CATALOG_ENTRIES)) {
			ChildCatalogEntry entry = ChildCatalogEntry.fromMap(doc.id(), doc.data());
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("child_pseudonym", ctx.pseudonyms().child(entry.childId()));
			row.put("parent_pseudonym", ctx.pseudonyms().parent(entry.parentId()));
			row.put("source_id", entry.sourceId());
			row.put("content_item_id", entry.contentItemId());
			row.put("status", entry.status().name());
			row.put("approved_date", TimePrecision.day(entry.approvedAt()));
			row.put("updated_date", TimePrecision.day(entry.updatedAt()));
			row.put("restriction_count", (long) entry.restrictions().size());
			rows.add(row);
		}
		return all(rows);
	}

	private static String ageBand(String birthYearMonth, ExportTable.Context ctx) {
		try {
			return AgeBands.of((int) YearMonth.parse(birthYearMonth).until(YearMonth.now(ctx.clock()), java.time.temporal.ChronoUnit.MONTHS));
		} catch (RuntimeException e) {
			return "unknown";
		}
	}

	private static Long longOrNull(Integer value) {
		return value == null ? null : value.longValue();
	}
}
