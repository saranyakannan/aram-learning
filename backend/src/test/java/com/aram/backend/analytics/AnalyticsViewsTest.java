package com.aram.backend.analytics;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyticsViewsTest {

	private final Map<String, String> views = AnalyticsViews.definitions("proj.children_content_analytics");

	@Test
	void everyViewThatCountsFamiliesHidesGroupsSmallerThanFive() {
		assertThat(AnalyticsViews.MIN_FAMILIES).isEqualTo(5);
		for (var entry : views.entrySet()) {
			if (entry.getValue().contains("parent_pseudonym")) {
				assertThat(entry.getValue()).as(entry.getKey())
						.contains("HAVING COUNT(DISTINCT parent_pseudonym) >= 5");
			}
		}
	}

	@Test
	void noViewExposesARawPseudonymColumnInItsSelectList() {
		for (var entry : views.entrySet()) {
			String select = entry.getValue().substring(0, entry.getValue().indexOf(" FROM "));
			assertThat(select).as(entry.getKey()).doesNotContain("child_pseudonym").doesNotContain("SELECT parent_pseudonym")
					.doesNotContain(", parent_pseudonym");
		}
	}

	@Test
	void theViewsReadOnlyTheBaseDatasetAndNeverTheRunLog() {
		for (String sql : views.values()) {
			assertThat(sql).contains("`proj.children_content_analytics.").doesNotContain(ExportTables.OPS_RUNS);
		}
		assertThat(views).containsKeys("v_catalog_sources", "v_interest_popularity", "v_approvals_by_source", "v_children_by_age_band");
	}
}
