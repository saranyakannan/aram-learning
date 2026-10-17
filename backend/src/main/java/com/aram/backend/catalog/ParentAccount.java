package com.aram.backend.catalog;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.aram.backend.catalog.FirestoreOps.bool;
import static com.aram.backend.catalog.FirestoreOps.instant;
import static com.aram.backend.catalog.FirestoreOps.string;
import static com.aram.backend.catalog.FirestoreOps.timestamp;

/**
 * {@code parents/{uid}} (spec 3.1); the id is the Firebase Authentication uid. {@code handle} is an extra for the
 * community features that are not built yet; {@code socialDiscoveryEnabled} stays false until they are.
 */
public record ParentAccount(
		String id,
		String displayName,
		String handle,
		String preferredLanguage,
		String accountStatus,
		boolean socialDiscoveryEnabled,
		Instant createdAt,
		Instant updatedAt
) {

	public Map<String, Object> toMap() {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("displayName", displayName);
		map.put("handle", handle);
		map.put("preferredLanguage", preferredLanguage);
		map.put("accountStatus", accountStatus);
		map.put("socialDiscoveryEnabled", socialDiscoveryEnabled);
		map.put("createdAt", timestamp(createdAt));
		map.put("updatedAt", timestamp(updatedAt));
		return map;
	}

	public static ParentAccount fromMap(String id, Map<String, Object> data) {
		return new ParentAccount(id, string(data, "displayName"), string(data, "handle"),
				string(data, "preferredLanguage"), string(data, "accountStatus"), bool(data, "socialDiscoveryEnabled"),
				instant(data.get("createdAt")), instant(data.get("updatedAt")));
	}
}
