package com.aram.backend.analytics;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Views analysts use instead of the base tables. Anything that counts families only appears when at least
 * {@link #MIN_FAMILIES} distinct families are behind a number, so a small group cannot be picked out.
 */
public final class AnalyticsViews {

	public static final int MIN_FAMILIES = 5;

	private AnalyticsViews() {
	}

	/** View name to its SQL; {@code base} is the fully qualified base dataset, e.g. {@code project.dataset}. */
	public static Map<String, String> definitions(String base) {
		Map<String, String> views = new LinkedHashMap<>();
		views.put("v_catalog_sources", "SELECT source_id, name, source_type, platform, tag_ids, catalog_status, assessment_status, "
				+ "min_age_months, max_age_months FROM `" + base + ".dim_content_sources`");
		views.put("v_interest_popularity", "SELECT tag_id, COUNT(DISTINCT parent_pseudonym) AS families FROM `" + base
				+ ".fact_parent_interests` GROUP BY tag_id HAVING COUNT(DISTINCT parent_pseudonym) >= " + MIN_FAMILIES);
		views.put("v_approvals_by_source", "SELECT source_id, status, COUNT(DISTINCT parent_pseudonym) AS families FROM `" + base
				+ ".fact_child_catalog_entries` GROUP BY source_id, status HAVING COUNT(DISTINCT parent_pseudonym) >= " + MIN_FAMILIES);
		views.put("v_children_by_age_band", "SELECT age_band, COUNT(DISTINCT parent_pseudonym) AS families, COUNT(*) AS children FROM `"
				+ base + ".dim_children` GROUP BY age_band HAVING COUNT(DISTINCT parent_pseudonym) >= " + MIN_FAMILIES);
		return views;
	}
}
