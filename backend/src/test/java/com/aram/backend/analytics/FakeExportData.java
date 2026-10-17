package com.aram.backend.analytics;

import com.google.cloud.Timestamp;
import com.aram.backend.catalog.FirestoreOps;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** An in-memory Firestore for the export tests. */
class FakeExportData implements ExportData {

	final Map<String, List<FirestoreOps.Doc>> collections = new LinkedHashMap<>();

	FakeExportData put(String collection, String id, Map<String, Object> data) {
		collections.computeIfAbsent(collection, key -> new ArrayList<>()).add(new FirestoreOps.Doc(id, data));
		return this;
	}

	@Override
	public List<FirestoreOps.Doc> all(String collection) {
		return collections.getOrDefault(collection, List.of());
	}

	@Override
	public List<FirestoreOps.Doc> atLeast(String collection, String field, Instant atLeast) {
		return all(collection).stream().filter(doc -> {
			Instant value = FirestoreOps.instant(doc.data().get(field));
			return value != null && !value.isBefore(atLeast);
		}).toList();
	}

	static Timestamp ts(String iso) {
		return FirestoreOps.timestamp(Instant.parse(iso));
	}

	static Map<String, Object> map(Object... keyValues) {
		Map<String, Object> map = new LinkedHashMap<>();
		for (int i = 0; i < keyValues.length; i += 2) {
			map.put((String) keyValues[i], keyValues[i + 1]);
		}
		return map;
	}
}
