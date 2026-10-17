package com.aram.backend.catalog;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.aram.backend.catalog.FirestoreOps.instant;
import static com.aram.backend.catalog.FirestoreOps.string;
import static com.aram.backend.catalog.FirestoreOps.timestamp;

/**
 * A parent's request to add a source, and where the agent got to with it (spec 3.9). The id is
 * {@code <childId>_<sourceId>}, so asking again about the same channel updates one record instead of piling up.
 * {@code READY_FOR_PARENT} is the staged state: the parent's Save turns it into a child catalog entry.
 */
public record ContentSubmission(
		String id,
		String submittedByParentId,
		String childId,
		String inputText,
		String inputUrl,
		String submissionType,
		Statuses.Submission status,
		String resolvedSourceId,
		String duplicateOfSourceId,
		String assessmentId,
		String failureReason,
		Instant createdAt,
		Instant updatedAt,
		Instant expiresAt
) {

	public static String idFor(String childId, String sourceId) {
		return childId + "_" + sourceId;
	}

	public ContentSubmission withStatus(Statuses.Submission next, Instant now) {
		return new ContentSubmission(id, submittedByParentId, childId, inputText, inputUrl, submissionType, next,
				resolvedSourceId, duplicateOfSourceId, assessmentId, failureReason, createdAt, now, expiresAt);
	}

	public Map<String, Object> toMap() {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("submittedByParentId", submittedByParentId);
		map.put("childId", childId);
		map.put("inputText", inputText);
		map.put("inputUrl", inputUrl);
		map.put("submissionType", submissionType);
		map.put("status", status.name());
		map.put("resolvedSourceId", resolvedSourceId);
		map.put("duplicateOfSourceId", duplicateOfSourceId);
		map.put("assessmentId", assessmentId);
		map.put("failureReason", failureReason);
		map.put("createdAt", timestamp(createdAt));
		map.put("updatedAt", timestamp(updatedAt));
		map.put("expiresAt", timestamp(expiresAt));
		return map;
	}

	public static ContentSubmission fromMap(String id, Map<String, Object> data) {
		return new ContentSubmission(
				id,
				string(data, "submittedByParentId"),
				string(data, "childId"),
				string(data, "inputText"),
				string(data, "inputUrl"),
				string(data, "submissionType"),
				Statuses.parse(Statuses.Submission.class, string(data, "status"), Statuses.Submission.FAILED),
				string(data, "resolvedSourceId"),
				string(data, "duplicateOfSourceId"),
				string(data, "assessmentId"),
				string(data, "failureReason"),
				instant(data.get("createdAt")),
				instant(data.get("updatedAt")),
				instant(data.get("expiresAt")));
	}
}
