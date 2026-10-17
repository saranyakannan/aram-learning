package com.aram.backend.catalog;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.aram.backend.catalog.FirestoreOps.string;

/**
 * Precomputed recommendation signals for one content source (decision record 0008): its nearest neighbours by embedding
 * similarity and its topic cluster. Derived from public catalog text only; it holds no family data and never decides
 * eligibility.
 */
public record SourceSignal(
		String sourceId,
		List<Neighbor> neighbors,
		Integer clusterId,
		String clusterLabel,
		String modelVersion,
		Instant computedAt
) {

	public record Neighbor(String sourceId, double similarity) {
	}

	public SourceSignal {
		neighbors = neighbors == null ? List.of() : List.copyOf(neighbors);
	}

	public Map<String, Object> toMap() {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("sourceId", sourceId);
		List<Map<String, Object>> list = new ArrayList<>();
		for (Neighbor neighbor : neighbors) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("sourceId", neighbor.sourceId());
			entry.put("similarity", neighbor.similarity());
			list.add(entry);
		}
		map.put("neighbors", list);
		map.put("clusterId", clusterId == null ? null : clusterId.longValue());
		map.put("clusterLabel", clusterLabel);
		map.put("modelVersion", modelVersion);
		map.put("computedAt", computedAt == null ? null : FirestoreOps.timestamp(computedAt));
		return map;
	}

	public static SourceSignal fromMap(String id, Map<String, Object> data) {
		List<Neighbor> neighbors = new ArrayList<>();
		if (data.get("neighbors") instanceof List<?> list) {
			for (Object item : list) {
				if (item instanceof Map<?, ?> entry && entry.get("sourceId") instanceof String neighborId
						&& entry.get("similarity") instanceof Number similarity) {
					neighbors.add(new Neighbor(neighborId, similarity.doubleValue()));
				}
			}
		}
		Long cluster = FirestoreOps.longValue(data, "clusterId");
		Object computed = data.get("computedAt");
		return new SourceSignal(id, neighbors, cluster == null ? null : cluster.intValue(), string(data, "clusterLabel"),
				string(data, "modelVersion"), computed == null ? null : FirestoreOps.instant(computed));
	}
}
