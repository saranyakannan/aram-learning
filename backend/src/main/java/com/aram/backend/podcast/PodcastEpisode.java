package com.aram.backend.podcast;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.aram.backend.catalog.FirestoreOps.integer;
import static com.aram.backend.catalog.FirestoreOps.string;

/**
 * One playable episode from a show's RSS feed. {@code id} is a short stable hash of the feed's episode GUID (or of the
 * audio link when there is no GUID), so it is safe in URLs. Only http(s) audio links are ever kept.
 */
public record PodcastEpisode(
		String id,
		String title,
		String description,
		String publishedAt,
		String audioUrl,
		String audioType,
		Integer durationSeconds,
		String imageUrl
) {

	public Map<String, Object> toMap() {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("id", id);
		map.put("title", title);
		map.put("description", description);
		map.put("publishedAt", publishedAt);
		map.put("audioUrl", audioUrl);
		map.put("audioType", audioType);
		map.put("durationSeconds", durationSeconds);
		map.put("imageUrl", imageUrl);
		return map;
	}

	public static PodcastEpisode fromMap(Map<String, Object> data) {
		return new PodcastEpisode(string(data, "id"), string(data, "title"), string(data, "description"),
				string(data, "publishedAt"), string(data, "audioUrl"), string(data, "audioType"),
				integer(data, "durationSeconds"), string(data, "imageUrl"));
	}
}
