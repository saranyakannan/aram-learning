package com.aram.backend.catalog;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.aram.backend.catalog.FirestoreOps.string;

/**
 * Precomputed recommendation signal for one curated book (decision record 0017): the catalog sources whose text is most
 * similar to the book's, by embedding similarity. Derived from public catalog text only; it holds no family data and never
 * decides what a child may see (the book's age range is still a hard filter).
 */
public record BookSignal(String bookId, List<SourceSignal.Neighbor> neighbors, String modelVersion, Instant computedAt) {

	public BookSignal {
		neighbors = neighbors == null ? List.of() : List.copyOf(neighbors);
	}

	public Map<String, Object> toMap() {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("bookId", bookId);
		List<Map<String, Object>> list = new ArrayList<>();
		for (SourceSignal.Neighbor neighbor : neighbors) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("sourceId", neighbor.sourceId());
			entry.put("similarity", neighbor.similarity());
			list.add(entry);
		}
		map.put("neighbors", list);
		map.put("modelVersion", modelVersion);
		map.put("computedAt", computedAt == null ? null : FirestoreOps.timestamp(computedAt));
		return map;
	}

	public static BookSignal fromMap(String id, Map<String, Object> data) {
		List<SourceSignal.Neighbor> neighbors = new ArrayList<>();
		if (data.get("neighbors") instanceof List<?> list) {
			for (Object item : list) {
				if (item instanceof Map<?, ?> entry && entry.get("sourceId") instanceof String sourceId
						&& entry.get("similarity") instanceof Number similarity) {
					neighbors.add(new SourceSignal.Neighbor(sourceId, similarity.doubleValue()));
				}
			}
		}
		Object computed = data.get("computedAt");
		return new BookSignal(id, neighbors, string(data, "modelVersion"), computed == null ? null : FirestoreOps.instant(computed));
	}
}
