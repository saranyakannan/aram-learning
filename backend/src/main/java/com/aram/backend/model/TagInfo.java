package com.aram.backend.model;

/** A taxonomy tag plus how many curated channels currently carry it (0 means no content yet). */
public record TagInfo(String id, String label, String icon, int channelCount) {
}
