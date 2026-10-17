package com.aram.backend.catalog.builder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.aram.backend.config.AramProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

/** The reviewed list of searches the builder runs ({@code catalog-queries.json}); changing it is a code review, not a runtime option. */
@Component
@ConditionalOnProperty(prefix = "aram.builder", name = "enabled", havingValue = "true")
public class CatalogQueries {

	public record Query(String tag, String query) {
	}

	public record Plan(List<Query> youtube, List<Query> podcasts) {
		public Plan {
			youtube = youtube == null ? List.of() : List.copyOf(youtube);
			podcasts = podcasts == null ? List.of() : List.copyOf(podcasts);
		}
	}

	private final ResourceLoader resourceLoader;
	private final AramProperties properties;
	private final ObjectMapper objectMapper;
	private Plan plan = new Plan(List.of(), List.of());

	public CatalogQueries(ResourceLoader resourceLoader, AramProperties properties, ObjectMapper objectMapper) {
		this.resourceLoader = resourceLoader;
		this.properties = properties;
		this.objectMapper = objectMapper;
	}

	@PostConstruct
	void load() {
		try (var input = resourceLoader.getResource(properties.getBuilder().getQueriesFile()).getInputStream()) {
			this.plan = objectMapper.readValue(input, Plan.class);
		} catch (IOException e) {
			throw new IllegalStateException("Unable to load the catalog queries from " + properties.getBuilder().getQueriesFile(), e);
		}
	}

	public Plan plan() {
		return plan;
	}
}
