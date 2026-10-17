package com.aram.backend.analytics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MergeSqlTest {

	private static ExportTable table(String name) {
		return ExportTables.all().stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
	}

	@Test
	void aSnapshotMergeAlsoDeletesRowsMissingFromTheSource() {
		String sql = MergeSql.merge("p.d.dim_parents", "p.d.stg", table("dim_parents"));

		assertThat(sql).contains("MERGE `p.d.dim_parents` T USING `p.d.stg` S ON T.parent_pseudonym = S.parent_pseudonym")
				.contains("WHEN NOT MATCHED BY SOURCE THEN DELETE")
				.contains("WHEN NOT MATCHED BY TARGET THEN INSERT")
				.contains("WHEN MATCHED AND T.row_hash != S.row_hash THEN UPDATE SET");
	}

	@Test
	void anIncrementalMergeNeverDeletes() {
		String sql = MergeSql.merge("p.d.fact_content_assessments", "p.d.stg", table("fact_content_assessments"));

		assertThat(sql).doesNotContain("DELETE").contains("T.assessment_id = S.assessment_id");
	}

	@Test
	void compositeKeysAreJoinedOnEveryKeyColumnAndKeysAreNotUpdated() {
		String sql = MergeSql.merge("p.d.bridge_content_tags", "p.d.stg", table("bridge_content_tags"));

		assertThat(sql).contains("T.target_type = S.target_type AND T.target_id = S.target_id AND T.tag_id = S.tag_id");
		assertThat(sql.substring(sql.indexOf("UPDATE SET"), sql.indexOf("WHEN NOT MATCHED BY TARGET")))
				.doesNotContain("target_id = S.target_id").contains("association_source = S.association_source");
	}

	@Test
	void everyColumnOfTheDefinitionIsInsertedAndNothingElse() {
		ExportTable children = table("dim_children");

		String sql = MergeSql.merge("p.d.dim_children", "p.d.stg", children);
		String insert = sql.substring(sql.indexOf("INSERT (") + 8, sql.indexOf(") VALUES"));

		assertThat(insert.split(", ")).containsExactlyElementsOf(children.allColumns().stream().map(Column::name).toList());
	}
}
