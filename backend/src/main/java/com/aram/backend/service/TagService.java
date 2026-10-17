package com.aram.backend.service;

import com.aram.backend.catalog.CatalogService;
import com.aram.backend.catalog.ContentSource;
import com.aram.backend.exception.InvalidRequestException;
import com.aram.backend.model.Tag;
import com.aram.backend.model.TagInfo;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class TagService {

	private final TagCatalogService tagCatalogService;
	private final CatalogService catalogService;

	public TagService(TagCatalogService tagCatalogService, CatalogService catalogService) {
		this.tagCatalogService = tagCatalogService;
		this.catalogService = catalogService;
	}

	/** Every taxonomy tag, in display order, with the number of recommendable channels carrying it (0 means no content yet). */
	public List<TagInfo> getTags() {
		List<ContentSource> sources = catalogService.recommendable();
		return tagCatalogService.getTags().stream()
				.map(tag -> new TagInfo(tag.id(), tag.label(), tag.icon(),
						(int) sources.stream().filter(source -> source.tagIds().contains(tag.id())).count()))
				.toList();
	}

	/** Returns de-duplicated canonical tag ids, or throws if any requested id is unknown. */
	public List<String> validate(Collection<String> requested) {
		if (requested == null) {
			throw new InvalidRequestException("tags must be provided");
		}
		Set<String> known = tagCatalogService.getTags().stream().map(Tag::id).collect(java.util.stream.Collectors.toSet());

		Set<String> result = new LinkedHashSet<>();
		for (String id : requested) {
			String normalized = id == null ? null : id.trim().toLowerCase();
			if (normalized == null || !known.contains(normalized)) {
				throw new InvalidRequestException("Unknown tag: '" + id + "'");
			}
			result.add(normalized);
		}
		return List.copyOf(result);
	}
}
