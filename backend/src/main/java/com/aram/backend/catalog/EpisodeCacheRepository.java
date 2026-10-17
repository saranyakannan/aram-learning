package com.aram.backend.catalog;

import com.aram.backend.podcast.PodcastEpisode;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The per-show "latest episodes" cache, so opening Kid Space doesn't fetch every feed each time. */
@Repository
public class EpisodeCacheRepository {

	public record Cached(List<PodcastEpisode> episodes, Instant fetchedAt) {
	}

	public static final String COLLECTION = "episode_cache";

	private final FirestoreOps firestore;

	public EpisodeCacheRepository(FirestoreOps firestore) {
		this.firestore = firestore;
	}

	public Optional<Cached> find(String sourceId) {
		return firestore.get(COLLECTION, sourceId).map(doc -> {
			List<PodcastEpisode> episodes = doc.data().get("episodes") instanceof List<?> list
					? list.stream().filter(Map.class::isInstance).map(item -> {
						@SuppressWarnings("unchecked")
						Map<String, Object> map = (Map<String, Object>) item;
						return PodcastEpisode.fromMap(map);
					}).toList()
					: List.of();
			return new Cached(episodes, FirestoreOps.instant(doc.data().get("fetchedAt")));
		});
	}

	public void save(String sourceId, List<PodcastEpisode> episodes, Instant fetchedAt) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("sourceId", sourceId);
		map.put("fetchedAt", FirestoreOps.timestamp(fetchedAt));
		map.put("episodes", episodes.stream().map(PodcastEpisode::toMap).toList());
		firestore.set(COLLECTION, sourceId, map);
	}
}
