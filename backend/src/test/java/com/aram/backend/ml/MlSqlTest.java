package com.aram.backend.ml;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MlSqlTest {

	private static final MlSql.Names NAMES = new MlSql.Names("proj", "us-central1", "analytics", "analytics_ml", "embedding_model",
			"text-embedding-005", "aram-vertex");

	private static List<String> all() {
		return List.of(MlSql.createDataset(NAMES), MlSql.createEmbeddingModel(NAMES), MlSql.inputs(NAMES), MlSql.embeddings(NAMES),
				MlSql.neighbors(NAMES, 8), MlSql.readNeighbors(NAMES), MlSql.readEmbeddings(NAMES), MlSql.countInputs(NAMES));
	}

	@Test
	void theEngineReadsOnlyPublicCatalogTablesAndNeverAFamilyTable() {
		for (String sql : all()) {
			String lower = sql.toLowerCase(Locale.ROOT);
			for (String family : List.of("dim_parents", "dim_children", "fact_parent", "fact_child", "ops_export_runs", "bridge_")) {
				assertThat(lower).as(family).doesNotContain(family);
			}
		}
		assertThat(MlSql.inputs(NAMES)).contains("`proj.analytics.dim_content_sources`").contains("`proj.analytics.dim_tags`");
		assertThat(MlSql.SOURCE_TABLES).containsExactly("dim_content_sources", "dim_tags");
	}

	@Test
	void onlyLiveClearedSourcesAreEmbedded() {
		assertThat(MlSql.inputs(NAMES)).contains("catalog_status = 'ACTIVE'")
				.contains("assessment_status IN ('ASSESSED', 'APPROVED_FOR_RECOMMENDATION')");
	}

	@Test
	void theRemoteModelAndEmbeddingCallUseTheDocumentedShape() {
		assertThat(MlSql.createEmbeddingModel(NAMES)).contains("REMOTE WITH CONNECTION `proj.us-central1.aram-vertex`")
				.contains("ENDPOINT = 'text-embedding-005'");
		assertThat(MlSql.embeddings(NAMES)).contains("ML.GENERATE_EMBEDDING(MODEL `proj.analytics_ml.embedding_model`")
				.contains("SELECT source_id, content FROM");
	}

	@Test
	void neighboursUseCosineSearchExcludeTheSourceItselfAndRequireOverlappingAges() {
		String sql = MlSql.neighbors(NAMES, 8);

		assertThat(sql).contains("VECTOR_SEARCH(").contains("distance_type => 'COSINE'").contains("r.query.source_id != r.base.source_id")
				.contains("a.min_age_months <= b.max_age_months").contains("neighbor_rank <= 8").contains("1 - r.distance AS similarity");
	}

	@Test
	void namesAreValidatedBeforeTheyReachSql() {
		assertThatThrownBy(() -> new MlSql.Names("proj", "us-central1", "a`; DROP", "ml", "m", "text-embedding-005", "c"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new MlSql.Names("proj", "us-central1", "a", "ml", "m", "x' OR '1", "c"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> MlSql.neighbors(NAMES, 0)).isInstanceOf(IllegalArgumentException.class);
	}
}
