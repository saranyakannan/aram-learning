package com.aram.backend.analytics;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * One analytics table: its columns (which are the whole privacy allowlist), its key, how it is exported and how its
 * rows are produced. Nothing outside {@link #columns()} can reach BigQuery, because rows are built column by column.
 */
public record ExportTable(
		String name,
		Mode mode,
		List<Column> columns,
		List<String> keyColumns,
		String partitionColumn,
		Extractor extractor
) {

	/** SNAPSHOT: the table becomes exactly the Firestore state (deletions propagate). INCREMENTAL: append/merge new rows only. */
	public enum Mode { SNAPSHOT, INCREMENTAL }

	public static final String ROW_HASH = "row_hash";

	public record Context(ExportData data, Pseudonymizer pseudonyms, Clock clock, String previousWatermark) {
	}

	/** {@code watermark} is only used by incremental tables: the newest source timestamp seen, as an ISO instant. */
	public record Extraction(List<Map<String, Object>> rows, String watermark) {
	}

	@FunctionalInterface
	public interface Extractor {
		Extraction extract(Context context);
	}

	public ExportTable {
		columns = List.copyOf(columns);
		keyColumns = List.copyOf(keyColumns);
	}

	/** The columns plus the trailing {@code row_hash} used to skip unchanged rows. */
	public List<Column> allColumns() {
		List<Column> all = new ArrayList<>(columns);
		all.add(Column.string(ROW_HASH));
		return all;
	}

	/** Adds the row hash. Throws if the row has a value for a column that is not on the allowlist. */
	public Map<String, Object> seal(Map<String, Object> row) {
		java.util.Set<String> allowed = new java.util.HashSet<>();
		columns.forEach(column -> allowed.add(column.name()));
		for (String key : row.keySet()) {
			if (!allowed.contains(key)) {
				throw new IllegalStateException("Column '" + key + "' is not on the allowlist of table " + name);
			}
		}
		StringBuilder canonical = new StringBuilder();
		for (Column column : columns) {
			canonical.append(column.name()).append('=').append(String.valueOf(row.get(column.name()))).append('\u0001');
		}
		Map<String, Object> sealed = new java.util.LinkedHashMap<>();
		for (Column column : columns) {
			sealed.put(column.name(), row.get(column.name()));
		}
		sealed.put(ROW_HASH, sha256(canonical.toString()));
		return sealed;
	}

	private static String sha256(String text) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
					.substring(0, 32);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
