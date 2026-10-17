package com.aram.backend.analytics;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Everything the export needs from BigQuery; the real one is {@link BigQueryJobSink}, tests use a fake. */
public interface BigQuerySink {

	/**
	 * Administrator set-up: {@link #prepare} plus the analyst views and their authorisation. Needs permission on the views dataset,
	 * so only the manual {@code --init-only} run does it.
	 */
	void initialize(List<ExportTable> tables);

	/** What every export run needs: the dataset and tables exist (new columns are added), and each matches its definition. */
	void prepare(List<ExportTable> tables);

	/** Throws {@link SchemaDriftException} if the live table differs from the definition. */
	void validateSchema(ExportTable table);

	long countRows(ExportTable table);

	/** The watermark of the latest successful run of an incremental table. */
	Optional<String> lastWatermark(String tableName);

	/** Loads the rows into a staging table, merges them into the target, and drops the staging table. */
	void apply(ExportTable table, List<Map<String, Object>> rows, String runId);

	void recordRun(RunRecord record);

	/** Deletes every row of one family (by parent pseudonym) from every family table; returns rows deleted per table. */
	Map<String, Long> purgeFamily(String parentPseudonym);
}
