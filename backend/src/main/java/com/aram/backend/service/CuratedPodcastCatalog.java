package com.aram.backend.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aram.backend.config.AramProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

/** Loads the starter list of podcasts from {@code podcasts.json}. Only the seed job reads it, as with {@code channels.json}. */
@Service
public class CuratedPodcastCatalog {

	/** One starter show, identified by its Apple Podcasts id (names alone are ambiguous); ages are whole years. */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record CuratedPodcast(long appleId, String name, String description, Integer minAge, Integer maxAge,
			List<String> tags) {
		public CuratedPodcast {
			tags = tags == null ? List.of() : List.copyOf(tags);
		}
	}

	private final ResourceLoader resourceLoader;
	private final AramProperties properties;
	private final ObjectMapper objectMapper;

	private List<CuratedPodcast> podcasts = List.of();

	public CuratedPodcastCatalog(ResourceLoader resourceLoader, AramProperties properties, ObjectMapper objectMapper) {
		this.resourceLoader = resourceLoader;
		this.properties = properties;
		this.objectMapper = objectMapper;
	}

	@PostConstruct
	void load() {
		try (var input = resourceLoader.getResource(properties.getPodcastsFile()).getInputStream()) {
			this.podcasts = List.of(objectMapper.readValue(input, CuratedPodcast[].class));
		} catch (IOException e) {
			throw new IllegalStateException("Unable to load podcasts from " + properties.getPodcastsFile(), e);
		}
	}

	public List<CuratedPodcast> getPodcasts() {
		return podcasts;
	}
}
