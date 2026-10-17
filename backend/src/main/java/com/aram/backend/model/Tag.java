package com.aram.backend.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * An interest tag from the reviewed taxonomy in {@code tags.json}; {@code icon} is a Material Symbols name.
 * Ages are in months, as the data model requires.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Tag(String id, String label, String icon, String categoryId, String description,
		Integer minAgeMonths, Integer maxAgeMonths) {

	public Tag(String id, String label, String icon) {
		this(id, label, icon, null, null, null, null);
	}
}
