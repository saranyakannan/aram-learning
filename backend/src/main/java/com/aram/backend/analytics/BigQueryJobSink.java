package com.aram.backend.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.bigquery.Acl;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryException;
import com.google.cloud.bigquery.Dataset;
import com.google.cloud.bigquery.DatasetId;
import com.google.cloud.bigquery.DatasetInfo;
import com.google.cloud.bigquery.Field;
import com.google.cloud.bigquery.FieldValueList;
import com.google.cloud.bigquery.FormatOptions;
import com.google.cloud.bigquery.InsertAllRequest;
import com.google.cloud.bigquery.InsertAllResponse;
import com.google.cloud.bigquery.Job;
import com.google.cloud.bigquery.JobInfo;
import com.google.cloud.bigquery.JobStatistics;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.QueryParameterValue;
import com.google.cloud.bigquery.Schema;
import com.google.cloud.bigquery.StandardSQLTypeName;
import com.google.cloud.bigquery.StandardTableDefinition;
import com.google.cloud.bigquery.Table;
import com.google.cloud.bigquery.TableDataWriteChannel;
import com.google.cloud.bigquery.TableId;
import com.google.cloud.bigquery.TableInfo;
import com.google.cloud.bigquery.TimePartitioning;
import com.google.cloud.bigquery.ViewDefinition;
import com.google.cloud.bigquery.WriteChannelConfiguration;
import com.aram.backend.config.AramProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The real BigQuery side of the export. Rows go to a staging table with a (free) batch load job and are then MERGEd into the
 * target, so a re-run is idempotent and nothing sits in the streaming buffer. Retryable BigQuery errors are surfaced as
 * {@link TransientExportException}. Only counts, names and statuses are ever logged.
 */
@Component
@ConditionalOnProperty(prefix = "aram.export", name = "enabled", havingValue = "true")
public class BigQueryJobSink implements BigQuerySink {

	private static final Logger log = LoggerFactory.getLogger(BigQueryJobSink.class);
	private static final long DAY_MILLIS = 24L * 60 * 60 * 1000;

	private final BigQuery bigQuery;
	private final ObjectMapper objectMapper;
	private final String project;
	private final String dataset;
	private final String viewsDataset;
	private final String location;
	private final int opsRetentionDays;

	public BigQueryJobSink(BigQuery bigQuery, ObjectMapper objectMapper, AramProperties properties) {
		AramProperties.Export export = properties.getExport();
		this.bigQuery = bigQuery;
		this.objectMapper = objectMapper;
		this.project = bigQuery.getOptions().getProjectId();
		this.dataset = export.getDataset();
		this.viewsDataset = export.getViewsDataset();
		this.location = export.getLocation();
		this.opsRetentionDays = export.getOpsRetentionDays();
	}

	// ----- setup and validation -----

	@Override
	public void initialize(List<ExportTable> tables) {
		guard(() -> {
			createAndValidate(tables);
			ensureViews();
			return null;
		});
	}

	@Override
	public void prepare(List<ExportTable> tables) {
		guard(() -> {
			createAndValidate(tables);
			return null;
		});
	}

	private void createAndValidate(List<ExportTable> tables) {
		ensureDataset(dataset, "Pseudonymised analytics copy of the Aram catalog and family choices");
		for (ExportTable table : tables) {
			ensureTable(table.name(), table.allColumns(), table.partitionColumn(), null);
		}
		ensureTable(ExportTables.OPS_RUNS, ExportTables.opsColumns(), "finished_at", opsRetentionDays * DAY_MILLIS);
		for (ExportTable table : tables) {
			validateSchema(table);
		}
	}

	@Override
	public void validateSchema(ExportTable table) {
		Table live = guard(() -> bigQuery.getTable(TableId.of(dataset, table.name())));
		if (live == null) {
			throw new SchemaDriftException("Table " + table.name() + " does not exist");
		}
		List<Field> actual = live.getDefinition().getSchema() == null ? List.of() : live.getDefinition().getSchema().getFields();
		describeDrift(table.name(), actual, table.allColumns()).ifPresent(message -> {
			throw new SchemaDriftException(message);
		});
	}

	/**
	 * Compares a live schema with the definition by column name (order does not matter: a column added later by the
	 * additive migration sits after {@code row_hash}). Any missing, unexpected or differently typed column is drift.
	 */
	static java.util.Optional<String> describeDrift(String table, List<Field> actual, List<Column> expected) {
		Map<String, Field> byName = new java.util.HashMap<>();
		actual.forEach(field -> byName.put(field.getName(), field));
		for (Column column : expected) {
			Field field = byName.get(column.name());
			if (field == null) {
				return java.util.Optional.of("Table " + table + " is missing column '" + column.name() + "'");
			}
			boolean repeated = field.getMode() == Field.Mode.REPEATED;
			if (field.getType().getStandardType() != standardType(column) || repeated != column.repeated()) {
				return java.util.Optional.of("Table " + table + " column '" + column.name() + "' has a different type than expected");
			}
		}
		java.util.Set<String> expectedNames = new java.util.HashSet<>();
		expected.forEach(column -> expectedNames.add(column.name()));
		for (Field field : actual) {
			if (!expectedNames.contains(field.getName())) {
				return java.util.Optional.of("Table " + table + " has an unexpected column '" + field.getName() + "'");
			}
		}
		return java.util.Optional.empty();
	}

	/** The expected columns that the live table does not have yet (the only change the migration is allowed to make). */
	static List<Column> missingColumns(List<Field> actual, List<Column> expected) {
		java.util.Set<String> present = new java.util.HashSet<>();
		actual.forEach(field -> present.add(field.getName()));
		return expected.stream().filter(column -> !present.contains(column.name())).toList();
	}

	private void ensureDataset(String name, String description) {
		DatasetId id = DatasetId.of(project, name);
		if (bigQuery.getDataset(id) == null) {
			bigQuery.create(DatasetInfo.newBuilder(id).setLocation(location).setDescription(description)
					.setLabels(Map.of("app", "aram", "data", "analytics")).build());
			log.info("Created dataset {} in {}", name, location);
		}
	}

	private void ensureTable(String name, List<Column> columns, String partitionColumn, Long expirationMillis) {
		TableId id = TableId.of(dataset, name);
		Table existing = bigQuery.getTable(id);
		if (existing != null) {
			addMissingColumns(existing, columns);
			return;
		}
		StandardTableDefinition.Builder definition = StandardTableDefinition.newBuilder().setSchema(schema(columns));
		if (partitionColumn != null) {
			TimePartitioning.Builder partitioning = TimePartitioning.newBuilder(TimePartitioning.Type.DAY).setField(partitionColumn);
			if (expirationMillis != null) {
				partitioning.setExpirationMs(expirationMillis);
			}
			definition.setTimePartitioning(partitioning.build());
		}
		bigQuery.create(TableInfo.of(id, definition.build()));
		log.info("Created table {}", name);
	}

	/** Additive migration: appends columns the definition gained since the table was created. Never drops or retypes one. */
	private void addMissingColumns(Table existing, List<Column> columns) {
		Schema current = existing.getDefinition().getSchema();
		List<Field> fields = current == null ? List.of() : current.getFields();
		List<Column> missing = missingColumns(fields, columns);
		if (missing.isEmpty()) {
			return;
		}
		List<Field> updated = new ArrayList<>(fields);
		schema(missing).getFields().forEach(updated::add);
		StandardTableDefinition definition = existing.getDefinition();
		bigQuery.update(existing.toBuilder().setDefinition(definition.toBuilder().setSchema(Schema.of(updated)).build()).build());
		log.info("Added columns {} to table {}", missing.stream().map(Column::name).toList(), existing.getTableId().getTable());
	}

	private void ensureViews() {
		ensureDataset(viewsDataset, "Analyst-facing views with small-group thresholds");
		Dataset base = bigQuery.getDataset(DatasetId.of(project, dataset));
		List<TableId> viewIds = new ArrayList<>();
		for (Map.Entry<String, String> view : AnalyticsViews.definitions(project + "." + dataset).entrySet()) {
			TableId viewId = TableId.of(project, viewsDataset, view.getKey());
			ViewDefinition definition = ViewDefinition.newBuilder(view.getValue()).setUseLegacySql(false).build();
			Table existing = bigQuery.getTable(viewId);
			if (existing == null) {
				bigQuery.create(TableInfo.of(viewId, definition));
			} else {
				bigQuery.update(existing.toBuilder().setDefinition(definition).build());
			}
			viewIds.add(viewId);
		}
		List<Acl> current = base.getAcl() == null ? List.of() : base.getAcl();
		List<Acl> authorized = withViewAuthorizations(current, viewIds);
		if (authorized.size() != current.size()) {
			bigQuery.update(base.toBuilder().setAcl(authorized).build());
		}
	}

	/**
	 * The dataset's access list plus every view that is not already authorised on it. Views are compared by their full id
	 * (project, dataset, table): BigQuery rejects duplicates, and an id without the project never matches the stored one.
	 */
	static List<Acl> withViewAuthorizations(List<Acl> existing, List<TableId> viewIds) {
		List<Acl> result = new ArrayList<>(existing);
		for (TableId viewId : viewIds) {
			boolean present = result.stream().anyMatch(entry -> entry.getEntity() instanceof Acl.View view
					&& view.getId().equals(viewId));
			if (!present) {
				result.add(Acl.of(new Acl.View(viewId)));
			}
		}
		return result;
	}

	// ----- reading state -----

	@Override
	public long countRows(ExportTable table) {
		return guard(() -> {
			var result = bigQuery.query(QueryJobConfiguration.newBuilder("SELECT COUNT(*) AS n FROM `" + fq(table.name()) + "`")
					.setUseLegacySql(false).build());
			return result.iterateAll().iterator().next().get("n").getLongValue();
		});
	}

	@Override
	public Optional<String> lastWatermark(String tableName) {
		return guard(() -> {
			var result = bigQuery.query(QueryJobConfiguration.newBuilder("SELECT watermark FROM `" + fq(ExportTables.OPS_RUNS)
							+ "` WHERE table_name = @t AND status = 'SUCCESS' AND watermark IS NOT NULL ORDER BY finished_at DESC LIMIT 1")
					.setUseLegacySql(false).addNamedParameter("t", QueryParameterValue.string(tableName)).build());
			for (FieldValueList row : result.iterateAll()) {
				return Optional.of(row.get("watermark").getStringValue());
			}
			return Optional.<String>empty();
		});
	}

	// ----- writing -----

	@Override
	public void apply(ExportTable table, List<Map<String, Object>> rows, String runId) {
		String stagingName = "stg_" + table.name() + "_" + runId.replace("-", "").substring(0, 8);
		TableId staging = TableId.of(dataset, stagingName);
		try {
			guard(() -> {
				Schema schema = schema(table.allColumns());
				if (rows.isEmpty()) {
					bigQuery.create(TableInfo.of(staging, StandardTableDefinition.of(schema)));
				} else {
					load(staging, schema, rows);
				}
				bigQuery.update(bigQuery.getTable(staging).toBuilder()
						.setExpirationTime(Instant.now().toEpochMilli() + DAY_MILLIS).build());
				Job job = bigQuery.create(JobInfo.of(QueryJobConfiguration.newBuilder(
						MergeSql.merge(fq(table.name()), fq(stagingName), table)).setUseLegacySql(false).build()));
				job = job.waitFor();
				if (job == null || job.getStatus().getError() != null) {
					throw new IllegalStateException("The merge into " + table.name() + " failed");
				}
				return null;
			});
		} finally {
			try {
				bigQuery.delete(staging);
			} catch (RuntimeException e) {
				log.warn("Could not drop staging table {}; it expires on its own", stagingName);
			}
		}
	}

	private void load(TableId staging, Schema schema, List<Map<String, Object>> rows) throws Exception {
		WriteChannelConfiguration configuration = WriteChannelConfiguration.newBuilder(staging)
				.setFormatOptions(FormatOptions.json()).setSchema(schema)
				.setWriteDisposition(JobInfo.WriteDisposition.WRITE_TRUNCATE)
				.setCreateDisposition(JobInfo.CreateDisposition.CREATE_IF_NEEDED).build();
		ByteArrayOutputStream ndjson = new ByteArrayOutputStream();
		for (Map<String, Object> row : rows) {
			ndjson.write(objectMapper.writeValueAsBytes(jsonRow(row)));
			ndjson.write('\n');
		}
		try (TableDataWriteChannel writer = bigQuery.writer(configuration)) {
			writer.write(ByteBuffer.wrap(ndjson.toByteArray()));
			writer.close();
			Job job = writer.getJob().waitFor();
			if (job == null || job.getStatus().getError() != null) {
				throw new IllegalStateException("The load into staging failed");
			}
		}
	}

	static Map<String, Object> jsonRow(Map<String, Object> row) {
		Map<String, Object> json = new LinkedHashMap<>();
		row.forEach((key, value) -> {
			if (value instanceof LocalDate || value instanceof Instant) {
				json.put(key, value.toString());
			} else if (value != null) {
				json.put(key, value);
			}
		});
		return json;
	}

	@Override
	public void recordRun(RunRecord record) {
		guard(() -> {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("run_id", record.runId());
			row.put("table_name", record.tableName());
			row.put("mode", record.mode());
			row.put("started_at", record.startedAt().toString());
			row.put("finished_at", record.finishedAt().toString());
			row.put("rows_read", record.rowsRead());
			row.put("rows_loaded", record.rowsLoaded());
			row.put("rows_before", record.rowsBefore());
			row.put("rows_after", record.rowsAfter());
			row.put("status", record.status());
			row.put("attempt", record.attempt());
			row.put("error_class", record.errorClass());
			row.put("watermark", record.watermark());
			row.values().removeIf(java.util.Objects::isNull);
			InsertAllResponse response = bigQuery.insertAll(InsertAllRequest.newBuilder(TableId.of(dataset, ExportTables.OPS_RUNS))
					.addRow(row).build());
			if (response.hasErrors()) {
				throw new IllegalStateException("Could not record the run");
			}
			return null;
		});
	}

	@Override
	public Map<String, Long> purgeFamily(String parentPseudonym) {
		Map<String, Long> deleted = new LinkedHashMap<>();
		for (ExportTable table : ExportTables.familyTables()) {
			deleted.put(table.name(), guard(() -> {
				Job job = bigQuery.create(JobInfo.of(QueryJobConfiguration.newBuilder("DELETE FROM `" + fq(table.name())
								+ "` WHERE parent_pseudonym = @p").setUseLegacySql(false)
						.addNamedParameter("p", QueryParameterValue.string(parentPseudonym)).build())).waitFor();
				JobStatistics.QueryStatistics statistics = job.getStatistics();
				return statistics.getNumDmlAffectedRows() == null ? 0L : statistics.getNumDmlAffectedRows();
			}));
		}
		return deleted;
	}

	// ----- helpers -----

	private String fq(String table) {
		return project + "." + dataset + "." + table;
	}

	static Schema schema(List<Column> columns) {
		List<Field> fields = new ArrayList<>();
		for (Column column : columns) {
			fields.add(Field.newBuilder(column.name(), standardType(column))
					.setMode(column.repeated() ? Field.Mode.REPEATED : Field.Mode.NULLABLE).build());
		}
		return Schema.of(fields);
	}

	static StandardSQLTypeName standardType(Column column) {
		return switch (column.type()) {
			case STRING -> StandardSQLTypeName.STRING;
			case INT64 -> StandardSQLTypeName.INT64;
			case FLOAT64 -> StandardSQLTypeName.FLOAT64;
			case BOOL -> StandardSQLTypeName.BOOL;
			case DATE -> StandardSQLTypeName.DATE;
			case TIMESTAMP -> StandardSQLTypeName.TIMESTAMP;
		};
	}

	@FunctionalInterface
	private interface Call<T> {
		T run() throws Exception;
	}

	/** Retryable BigQuery errors become {@link TransientExportException}; everything else passes through. */
	private <T> T guard(Call<T> call) {
		try {
			return call.run();
		} catch (BigQueryException e) {
			if (e.isRetryable() || e.getCode() == 429 || e.getCode() >= 500) {
				throw new TransientExportException("BigQuery temporarily failed (" + e.getCode() + ")", e);
			}
			throw e;
		} catch (RuntimeException e) {
			throw e;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new TransientExportException("Interrupted", e);
		} catch (Exception e) {
			throw new IllegalStateException(e.getClass().getSimpleName(), e);
		}
	}
}
