package com.aram.backend.catalog;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.aram.backend.catalog.FirestoreOps.instant;
import static com.aram.backend.catalog.FirestoreOps.string;
import static com.aram.backend.catalog.FirestoreOps.strings;
import static com.aram.backend.catalog.FirestoreOps.timestamp;

/**
 * The parent's decision about one source for one child (spec 3.13): the only record that authorizes playback.
 * Approval is at the source (channel) level for now, so future uploads from an approved channel are shown;
 * {@code contentItemId} stays null until item-level approval exists. The id is {@code <childId>_<sourceId>}.
 */
public record ChildCatalogEntry(
		String id,
		String parentId,
		String childId,
		String sourceId,
		String contentItemId,
		Statuses.Entry status,
		String approvedBy,
		Instant approvedAt,
		List<String> restrictions,
		Instant createdAt,
		Instant updatedAt
) {

	public ChildCatalogEntry {
		restrictions = restrictions == null ? List.of() : List.copyOf(restrictions);
	}

	public static String idFor(String childId, String sourceId) {
		return childId + "_" + sourceId;
	}

	public Map<String, Object> toMap() {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("parentId", parentId);
		map.put("childId", childId);
		map.put("sourceId", sourceId);
		map.put("contentItemId", contentItemId);
		map.put("status", status.name());
		map.put("approvedBy", approvedBy);
		map.put("approvedAt", timestamp(approvedAt));
		map.put("restrictions", restrictions);
		map.put("createdAt", timestamp(createdAt));
		map.put("updatedAt", timestamp(updatedAt));
		return map;
	}

	public static ChildCatalogEntry fromMap(String id, Map<String, Object> data) {
		return new ChildCatalogEntry(
				id,
				string(data, "parentId"),
				string(data, "childId"),
				string(data, "sourceId"),
				string(data, "contentItemId"),
				Statuses.parse(Statuses.Entry.class, string(data, "status"), Statuses.Entry.REVOKED),
				string(data, "approvedBy"),
				instant(data.get("approvedAt")),
				strings(data, "restrictions"),
				instant(data.get("createdAt")),
				instant(data.get("updatedAt")));
	}
}
