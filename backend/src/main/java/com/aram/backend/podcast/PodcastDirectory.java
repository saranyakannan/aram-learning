package com.aram.backend.podcast;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aram.backend.config.AramProperties;
import com.aram.backend.exception.PodcastLookupException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Finds podcasts in Apple's public directory (no key needed). The directory gives the show's name, publisher, artwork,
 * genre, explicit flag and its RSS feed address; the description and episodes come from the feed itself.
 */
@Component
public class PodcastDirectory {

	static final int MAX_CANDIDATES = 5;
	private static final int MAX_BODY = 1_000_000;

	private final HttpFetcher http;
	private final ObjectMapper objectMapper;
	private final String baseUrl;

	public PodcastDirectory(HttpFetcher http, ObjectMapper objectMapper, AramProperties properties) {
		this.http = http;
		this.objectMapper = objectMapper;
		this.baseUrl = properties.getPodcast().getDirectoryBaseUrl();
	}

	public List<PodcastFacts> search(String term) {
		String encoded = URLEncoder.encode(term.strip(), StandardCharsets.UTF_8);
		return read(baseUrl + "/search?media=podcast&entity=podcast&limit=" + MAX_CANDIDATES + "&term=" + encoded);
	}

	public Optional<PodcastFacts> findById(long collectionId) {
		return read(baseUrl + "/lookup?entity=podcast&id=" + collectionId).stream()
				.filter(show -> show.collectionId() == collectionId).findFirst();
	}

	private List<PodcastFacts> read(String url) {
		HttpFetcher.Response response = http.get(URI.create(url), MAX_BODY);
		if (response.status() != 200) {
			throw new PodcastLookupException("The podcast directory answered " + response.status());
		}
		try {
			JsonNode results = objectMapper.readTree(response.body()).path("results");
			List<PodcastFacts> shows = new ArrayList<>();
			for (JsonNode node : results) {
				PodcastFacts facts = toFacts(node);
				if (facts != null && shows.size() < MAX_CANDIDATES) {
					shows.add(facts);
				}
			}
			return shows;
		} catch (IOException e) {
			throw new PodcastLookupException("The podcast directory's answer couldn't be read", e);
		}
	}

	private static PodcastFacts toFacts(JsonNode node) {
		long id = node.path("collectionId").asLong(0);
		String title = node.path("collectionName").asText("");
		String feedUrl = PodcastFeedParser.httpUrlOrNull(node.path("feedUrl").asText(null));
		if (id <= 0 || title.isBlank() || feedUrl == null) {
			return null;
		}
		boolean explicit = "explicit".equalsIgnoreCase(node.path("trackExplicitness").asText(""))
				|| "explicit".equalsIgnoreCase(node.path("collectionExplicitness").asText(""));
		return new PodcastFacts(id, PodcastFeedParser.clean(title, 200), PodcastFeedParser.clean(node.path("artistName").asText(""), 100),
				"", feedUrl, PodcastFeedParser.httpUrlOrNull(node.path("artworkUrl600").asText(null)), explicit,
				node.path("primaryGenreName").asText(""));
	}
}
