package com.aram.backend.analytics;

import java.util.List;
import java.util.stream.Collectors;

/** Builds the MERGE that applies a staging table to a target table. Names and columns come only from the definition. */
public final class MergeSql {

	private MergeSql() {
	}

	/**
	 * Snapshot tables also delete target rows missing from the source (that is how Firestore deletions propagate);
	 * incremental tables never delete. Unchanged rows (same {@code row_hash}) are not rewritten.
	 */
	public static String merge(String targetFq, String stagingFq, ExportTable table) {
		List<String> columns = table.allColumns().stream().map(Column::name).toList();
		String on = table.keyColumns().stream().map(key -> "T." + key + " = S." + key).collect(Collectors.joining(" AND "));
		String update = columns.stream().filter(column -> !table.keyColumns().contains(column))
				.map(column -> column + " = S." + column).collect(Collectors.joining(", "));
		String insertColumns = String.join(", ", columns);
		String insertValues = columns.stream().map(column -> "S." + column).collect(Collectors.joining(", "));

		StringBuilder sql = new StringBuilder();
		sql.append("MERGE `").append(targetFq).append("` T USING `").append(stagingFq).append("` S ON ").append(on).append('\n');
		sql.append("WHEN MATCHED AND T.").append(ExportTable.ROW_HASH).append(" != S.").append(ExportTable.ROW_HASH)
				.append(" THEN UPDATE SET ").append(update).append('\n');
		sql.append("WHEN NOT MATCHED BY TARGET THEN INSERT (").append(insertColumns).append(") VALUES (").append(insertValues).append(")");
		if (table.mode() == ExportTable.Mode.SNAPSHOT) {
			sql.append('\n').append("WHEN NOT MATCHED BY SOURCE THEN DELETE");
		}
		return sql.toString();
	}
}
