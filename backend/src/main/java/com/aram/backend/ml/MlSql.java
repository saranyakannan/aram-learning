package com.aram.backend.ml;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The BigQuery ML statements of the recommendation engine. They read only the public catalog tables of the analytics
 * dataset ({@code dim_content_sources}, {@code dim_tags}); no family table is ever referenced, so the engine cannot see
 * parents, children, interests or choices. Names come from configuration and are validated before they enter SQL.
 */
public final class MlSql {

	public static final String INPUTS = "ml_source_inputs";
	public static final String EMBEDDINGS = "ml_source_embeddings";
	public static final String NEIGHBORS = "ml_source_neighbors";

	/** The only analytics tables the engine may read. */
	public static final List<String> SOURCE_TABLES = List.of("dim_content_sources", "dim_tags");

	private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z0-9_-]{1,128}$");
	private static final Pattern ENDPOINT = Pattern.compile("^[A-Za-z0-9._@-]{1,128}$");

	public record Names(String project, String location, String analyticsDataset, String mlDataset, String model, String endpoint,
			String connection) {

		public Names {
			for (String value : List.of(project, location, analyticsDataset, mlDataset, model, connection)) {
				if (value == null || !IDENTIFIER.matcher(value).matches()) {
					throw new IllegalArgumentException("Not a valid BigQuery identifier: " + value);
				}
			}
			if (endpoint == null || !ENDPOINT.matcher(endpoint).matches()) {
				throw new IllegalArgumentException("Not a valid embedding endpoint: " + endpoint);
			}
		}

		String ml(String table) {
			return "`" + project + "." + mlDataset + "." + table + "`";
		}

		String analytics(String table) {
			return "`" + project + "." + analyticsDataset + "." + table + "`";
		}
	}

	private MlSql() {
	}

	public static String createDataset(Names n) {
		return "CREATE SCHEMA IF NOT EXISTS `" + n.project() + "." + n.mlDataset() + "` OPTIONS (location = '" + n.location()
				+ "', description = 'Aram recommendation engine: embeddings, neighbours (public catalog text only)')";
	}

	/** A remote model over a Vertex AI text-embedding endpoint, through the BigQuery connection. */
	public static String createEmbeddingModel(Names n) {
		return "CREATE MODEL IF NOT EXISTS " + n.ml(n.model()) + " REMOTE WITH CONNECTION `" + n.project() + "." + n.location()
				+ "." + n.connection() + "` OPTIONS (ENDPOINT = '" + n.endpoint() + "')";
	}

	/** One text per live, cleared source: its public name, public description and topic names. Input column must be named content. */
	public static String inputs(Names n) {
		return "CREATE OR REPLACE TABLE " + n.ml(INPUTS) + " AS\n"
				+ "WITH topics AS (\n"
				+ "  SELECT s.source_id, STRING_AGG(t.name, ', ' ORDER BY t.name) AS topic_names\n"
				+ "  FROM " + n.analytics("dim_content_sources") + " AS s CROSS JOIN UNNEST(s.tag_ids) AS tid\n"
				+ "  JOIN " + n.analytics("dim_tags") + " AS t ON t.tag_id = tid\n"
				+ "  GROUP BY s.source_id)\n"
				+ "SELECT s.source_id, s.source_type, s.min_age_months, s.max_age_months,\n"
				+ "  CONCAT(s.name, '. ', IFNULL(s.description, ''), ' Topics: ', IFNULL(p.topic_names, '')) AS content\n"
				+ "FROM " + n.analytics("dim_content_sources") + " AS s LEFT JOIN topics AS p ON p.source_id = s.source_id\n"
				+ "WHERE s.catalog_status = 'ACTIVE' AND s.assessment_status IN ('ASSESSED', 'APPROVED_FOR_RECOMMENDATION')";
	}

	public static String embeddings(Names n) {
		return "CREATE OR REPLACE TABLE " + n.ml(EMBEDDINGS) + " AS\n"
				+ "SELECT source_id, content, ml_generate_embedding_result AS embedding, CURRENT_TIMESTAMP() AS computed_at\n"
				+ "FROM ML.GENERATE_EMBEDDING(MODEL " + n.ml(n.model()) + ",\n"
				+ "  (SELECT source_id, content FROM " + n.ml(INPUTS) + "), STRUCT(TRUE AS flatten_json_output))\n"
				+ "WHERE ARRAY_LENGTH(ml_generate_embedding_result) > 0";
	}

	/**
	 * Nearest neighbours by cosine distance, never the source itself and only sources whose age ranges overlap
	 * (a missing age counts as overlapping). {@code similarity = 1 - cosine distance}.
	 */
	public static String neighbors(Names n, int neighborCount) {
		if (neighborCount < 1 || neighborCount > 50) {
			throw new IllegalArgumentException("neighborCount must be 1-50");
		}
		int searchDepth = Math.min(neighborCount * 3 + 1, 200);
		return "CREATE OR REPLACE TABLE " + n.ml(NEIGHBORS) + " AS\n"
				+ "SELECT source_id, neighbor_id, similarity, neighbor_rank FROM (\n"
				+ "  SELECT r.query.source_id AS source_id, r.base.source_id AS neighbor_id, 1 - r.distance AS similarity,\n"
				+ "    ROW_NUMBER() OVER (PARTITION BY r.query.source_id ORDER BY r.distance, r.base.source_id) AS neighbor_rank\n"
				+ "  FROM VECTOR_SEARCH(TABLE " + n.ml(EMBEDDINGS) + ", 'embedding', TABLE " + n.ml(EMBEDDINGS) + ", 'embedding',\n"
				+ "    top_k => " + searchDepth + ", distance_type => 'COSINE', options => '{\"use_brute_force\":true}') AS r\n"
				+ "  JOIN " + n.ml(INPUTS) + " AS a ON a.source_id = r.query.source_id\n"
				+ "  JOIN " + n.ml(INPUTS) + " AS b ON b.source_id = r.base.source_id\n"
				+ "  WHERE r.query.source_id != r.base.source_id\n"
				+ "    AND (a.min_age_months IS NULL OR b.max_age_months IS NULL OR a.min_age_months <= b.max_age_months)\n"
				+ "    AND (b.min_age_months IS NULL OR a.max_age_months IS NULL OR b.min_age_months <= a.max_age_months)\n"
				+ ") WHERE neighbor_rank <= " + neighborCount;
	}

	public static String readNeighbors(Names n) {
		return "SELECT source_id, neighbor_id, CAST(similarity AS STRING) AS similarity, CAST(neighbor_rank AS STRING) AS neighbor_rank FROM "
				+ n.ml(NEIGHBORS) + " ORDER BY source_id, neighbor_rank";
	}

	public static String readEmbeddings(Names n) {
		return "SELECT source_id, TO_JSON_STRING(embedding) AS embedding_json FROM " + n.ml(EMBEDDINGS) + " ORDER BY source_id";
	}

	public static String countInputs(Names n) {
		return "SELECT CAST(COUNT(*) AS STRING) AS n FROM " + n.analytics("dim_content_sources")
				+ " WHERE catalog_status = 'ACTIVE' AND assessment_status IN ('ASSESSED', 'APPROVED_FOR_RECOMMENDATION')";
	}
}
