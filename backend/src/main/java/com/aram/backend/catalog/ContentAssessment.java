package com.aram.backend.catalog;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.aram.backend.catalog.FirestoreOps.instant;
import static com.aram.backend.catalog.FirestoreOps.integer;
import static com.aram.backend.catalog.FirestoreOps.string;
import static com.aram.backend.catalog.FirestoreOps.strings;
import static com.aram.backend.catalog.FirestoreOps.timestamp;

/**
 * One append-only assessment of a source (spec 3.8). Older assessments are never edited; the source points at the
 * latest. {@code verdict} and {@code confidenceLabel} are the model's own words (the spec's {@code confidence} stays
 * null because they are not calibrated probabilities); {@code summary} is the explanation shown to the parent.
 */
public record ContentAssessment(
		String id,
		String targetType,
		String targetId,
		Statuses.Assessor assessorType,
		Statuses.Assessment assessmentStatus,
		String verdict,
		String confidenceLabel,
		Integer minAgeMonths,
		Integer maxAgeMonths,
		List<String> suggestedTagIds,
		List<String> learningGoals,
		String presentationStyle,
		List<String> safetyFindings,
		String summary,
		String evidenceLevel,
		List<String> evidenceReferences,
		List<String> limitations,
		String modelName,
		String modelVersion,
		String policyVersion,
		Integer forChildAgeMonths,
		Instant assessedAt,
		String reviewedBy,
		Instant reviewedAt
) {

	public static final String CONTENT_SOURCE = "CONTENT_SOURCE";
	public static final String POLICY_VERSION = "content-policy-v1";
	public static final String METADATA_ONLY = "METADATA_ONLY";
	public static final String DEVELOPER_REVIEW = "DEVELOPER_REVIEW";

	public ContentAssessment {
		suggestedTagIds = suggestedTagIds == null ? List.of() : List.copyOf(suggestedTagIds);
		learningGoals = learningGoals == null ? List.of() : List.copyOf(learningGoals);
		safetyFindings = safetyFindings == null ? List.of() : List.copyOf(safetyFindings);
		evidenceReferences = evidenceReferences == null ? List.of() : List.copyOf(evidenceReferences);
		limitations = limitations == null ? List.of() : List.copyOf(limitations);
	}

	public ContentAssessment withId(String newId) {
		return new ContentAssessment(newId, targetType, targetId, assessorType, assessmentStatus, verdict, confidenceLabel,
				minAgeMonths, maxAgeMonths, suggestedTagIds, learningGoals, presentationStyle, safetyFindings, summary,
				evidenceLevel, evidenceReferences, limitations, modelName, modelVersion, policyVersion, forChildAgeMonths,
				assessedAt, reviewedBy, reviewedAt);
	}

	public Map<String, Object> toMap() {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("targetType", targetType);
		map.put("targetId", targetId);
		map.put("assessorType", assessorType.name());
		map.put("assessmentStatus", assessmentStatus.name());
		map.put("verdict", verdict);
		map.put("confidence", null);
		map.put("confidenceLabel", confidenceLabel);
		map.put("minAgeMonths", minAgeMonths);
		map.put("maxAgeMonths", maxAgeMonths);
		map.put("suggestedTagIds", suggestedTagIds);
		map.put("learningGoals", learningGoals);
		map.put("presentationStyle", presentationStyle);
		map.put("safetyFindings", safetyFindings);
		map.put("summary", summary);
		map.put("evidenceLevel", evidenceLevel);
		map.put("evidenceReferences", evidenceReferences);
		map.put("limitations", limitations);
		map.put("modelName", modelName);
		map.put("modelVersion", modelVersion);
		map.put("policyVersion", policyVersion);
		map.put("forChildAgeMonths", forChildAgeMonths);
		map.put("assessedAt", timestamp(assessedAt));
		map.put("reviewedBy", reviewedBy);
		map.put("reviewedAt", timestamp(reviewedAt));
		return map;
	}

	public static ContentAssessment fromMap(String id, Map<String, Object> data) {
		return new ContentAssessment(
				id,
				string(data, "targetType"),
				string(data, "targetId"),
				Statuses.parse(Statuses.Assessor.class, string(data, "assessorType"), Statuses.Assessor.LLM),
				Statuses.parse(Statuses.Assessment.class, string(data, "assessmentStatus"), Statuses.Assessment.PENDING),
				string(data, "verdict"),
				string(data, "confidenceLabel"),
				integer(data, "minAgeMonths"),
				integer(data, "maxAgeMonths"),
				strings(data, "suggestedTagIds"),
				strings(data, "learningGoals"),
				string(data, "presentationStyle"),
				strings(data, "safetyFindings"),
				string(data, "summary"),
				string(data, "evidenceLevel"),
				strings(data, "evidenceReferences"),
				strings(data, "limitations"),
				string(data, "modelName"),
				string(data, "modelVersion"),
				string(data, "policyVersion"),
				integer(data, "forChildAgeMonths"),
				instant(data.get("assessedAt")),
				string(data, "reviewedBy"),
				instant(data.get("reviewedAt")));
	}
}
