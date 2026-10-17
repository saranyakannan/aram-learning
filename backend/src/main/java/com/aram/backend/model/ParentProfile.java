package com.aram.backend.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * What the UI sees of a family: the parent, their (first) child, the interests they picked and the channels they
 * approved. It is assembled from the {@code parents}, {@code children}, {@code parent_interests} and
 * {@code child_catalog_entries} collections, not stored as one document. A parent who has not approved anything yet
 * has an empty {@code channelIds}: nothing is shown to the child until the parent chooses.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ParentProfile {
	private String parentName;
	private String handle;
	private String childName;
	/** Birth month as {@code YYYY-MM}. */
	private String birthYearMonth;
	/** Whole years, computed from the birth month. */
	private Integer childAge;
	private Integer childAgeMonths;
	private List<String> tags = List.of();
	private List<String> channelIds = List.of();
}
