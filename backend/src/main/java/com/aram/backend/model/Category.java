package com.aram.backend.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** A broad learning area from {@code categories.json}; tags belong to exactly one category. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Category(String id, String name, String description, String icon, int sortOrder) {
}
