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
				MlSql.neighbors(NAMES, 8), MlSql.readNeighbors(NAMES), MlSql.readEmbeddings(NAMES), MlSql.countInputs(NAMES),
				MlSql.bookInputs(NAMES), MlSql.bookEmbeddings(NAMES), MlSql.bookNeighbors(NAMES, 8), MlSql.readBookNeighbors(NAMES));
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

	@Test
	void bookTextReachesBigQueryOnlyAsParametersNeverInsideTheSql() {
		String sql = MlSql.bookInputs(NAMES);

		assertThat(sql).contains("@ids").contains("@mins").contains("@maxs").contains("@contents")
				.contains("`proj.analytics_ml.ml_book_inputs`");
		assertThat(sql).doesNotContain("Gruffalo").doesNotContain("'");
	}

	@Test
	void booksAreMatchedAgainstSourcesAndNeverTheOtherWayRound() {
		String sql = MlSql.bookNeighbors(NAMES, 5);

		// base (neighbours) are the sources, query (the thing looked up) are the books
		assertThat(sql).contains("VECTOR_SEARCH(TABLE `proj.analytics_ml.ml_source_embeddings`, 'embedding', TABLE `proj.analytics_ml.ml_book_embeddings`")
				.contains("r.query.book_id").contains("r.base.source_id").contains("neighbor_rank <= 5")
				.contains("distance_type => 'COSINE'");
		// ages must overlap, and only the ML dataset is read (the sources' inputs live there too)
		assertThat(sql).contains("a.min_age_months <= b.max_age_months").contains("b.min_age_months <= a.max_age_months");
		assertThat(sql).doesNotContain("`proj.analytics.");
		assertThat(MlSql.neighbors(NAMES, 5)).as("source-to-source neighbours are untouched by books").doesNotContain("book");
		assertThatThrownBy(() -> MlSql.bookNeighbors(NAMES, 0)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> MlSql.bookNeighbors(NAMES, 51)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void bookEmbeddingsUseTheSameRemoteModelAsTheSources() {
		assertThat(MlSql.bookEmbeddings(NAMES)).contains("ML.GENERATE_EMBEDDING(MODEL `proj.analytics_ml.embedding_model`")
				.contains("`proj.analytics_ml.ml_book_inputs`");
	}
}
