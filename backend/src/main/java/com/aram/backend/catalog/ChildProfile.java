package com.aram.backend.catalog;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.aram.backend.catalog.FirestoreOps.instant;
import static com.aram.backend.catalog.FirestoreOps.string;
import static com.aram.backend.catalog.FirestoreOps.timestamp;

/**
 * {@code children/{childId}} (spec 3.2). Only the birth month is kept (the spec allows the minimum age information
 * the product needs), and the age is computed from it so it never goes stale.
 */
public record ChildProfile(
		String id,
		String parentId,
		String displayName,
		String birthYearMonth,
		String preferredLanguage,
		String profileStatus,
		Instant createdAt,
		Instant updatedAt
) {

	/** One child per parent for now; later children would get {@code -c2} and so on. */
	public static String firstChildId(String parentId) {
		return parentId + "-c1";
	}

	public Map<String, Object> toMap() {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("parentId", parentId);
		map.put("displayName", displayName);
		map.put("birthYearMonth", birthYearMonth);
		map.put("preferredLanguage", preferredLanguage);
		map.put("profileStatus", profileStatus);
		map.put("createdAt", timestamp(createdAt));
		map.put("updatedAt", timestamp(updatedAt));
		return map;
	}

	public static ChildProfile fromMap(String id, Map<String, Object> data) {
		return new ChildProfile(id, string(data, "parentId"), string(data, "displayName"),
				string(data, "birthYearMonth"), string(data, "preferredLanguage"), string(data, "profileStatus"),
				instant(data.get("createdAt")), instant(data.get("updatedAt")));
	}
}
