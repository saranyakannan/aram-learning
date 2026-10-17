package com.aram.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.aram.backend.config.AramProperties;
import com.aram.backend.model.Category;
import com.aram.backend.model.Tag;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

/**
 * Loads the reviewed taxonomy (categories and interest tags) from {@code categories.json} and {@code tags.json}.
 * The files are the reviewed source of truth; {@link com.aram.backend.catalog.CatalogSeeder} mirrors them
 * into the Firestore {@code categories} and {@code tags} collections so analytics can read them there.
 */
@Service
public class TagCatalogService {

	private final ResourceLoader resourceLoader;
	private final AramProperties properties;
	private final ObjectMapper objectMapper;

	private List<Tag> tags = List.of();
	private List<Category> categories = List.of();

	public TagCatalogService(ResourceLoader resourceLoader, AramProperties properties, ObjectMapper objectMapper) {
		this.resourceLoader = resourceLoader;
		this.properties = properties;
		this.objectMapper = objectMapper;
	}

	@PostConstruct
	void loadTags() {
		try (var input = resourceLoader.getResource(properties.getTagsFile()).getInputStream()) {
			this.tags = List.of(objectMapper.readValue(input, Tag[].class));
		} catch (IOException e) {
			throw new IllegalStateException("Unable to load tags from " + properties.getTagsFile(), e);
		}
		try (var input = resourceLoader.getResource(properties.getCategoriesFile()).getInputStream()) {
			this.categories = List.of(objectMapper.readValue(input, Category[].class));
		} catch (IOException e) {
			throw new IllegalStateException("Unable to load categories from " + properties.getCategoriesFile(), e);
		}
	}

	public List<Category> getCategories() {
		return categories;
	}

	/** The display name of a tag's category, or "Other" when the tag or category is unknown. */
	public String categoryNameForTag(String tagId) {
		return tags.stream().filter(tag -> tag.id().equals(tagId)).findFirst()
				.flatMap(tag -> categories.stream().filter(category -> category.id().equals(tag.categoryId())).findFirst())
				.map(Category::name).orElse("Other");
	}

	public List<Tag> getTags() {
		return tags;
	}
}
