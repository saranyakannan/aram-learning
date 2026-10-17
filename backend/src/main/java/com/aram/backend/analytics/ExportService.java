package com.aram.backend.analytics;

import com.aram.backend.config.AramProperties;
import com.aram.backend.exception.ProfileStorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Runs the Firestore-to-BigQuery export, one table at a time: read, apply the allowlist, check the live schema, guard
 * against mass deletes, load and merge, then record the run. A table that keeps failing does not stop the others; the
 * run as a whole reports failure. An incremental table's checkpoint only advances when its run is recorded as a success.
 */
@Service
@ConditionalOnProperty(prefix = "aram.export", name = "enabled", havingValue = "true")
public class ExportService {

	public record Options(boolean dryRun, boolean force, Set<String> onlyTables) {
		public static Options defaults() {
			return new Options(false, false, Set.of());
		}
	}

	public record TableResult(String table, String status, int attempts, long rowsRead, Long rowsBefore, Long rowsAfter,
			String watermark, String errorClass) {
	}

	@FunctionalInterface
	interface Sleeper {
		void sleep(long millis) throws InterruptedException;
	}

	static final int MAX_ATTEMPTS = 3;
	private static final Logger log = LoggerFactory.getLogger(ExportService.class);

	private final ExportData data;
	private final Pseudonymizer pseudonyms;
	private final BigQuerySink sink;
	private final Clock clock;
	private final int massDeleteGuardMinRows;
	private final Sleeper sleeper;
	private final Supplier<String> runIds;
	private final List<ExportTable> tables;

	@Autowired
	public ExportService(ExportData data, Pseudonymizer pseudonyms, BigQuerySink sink, Clock clock, AramProperties properties) {
		this(data, pseudonyms, sink, clock, properties.getExport().getMassDeleteGuardMinRows(), Thread::sleep,
				() -> UUID.randomUUID().toString(), ExportTables.all());
	}

	ExportService(ExportData data, Pseudonymizer pseudonyms, BigQuerySink sink, Clock clock, int massDeleteGuardMinRows,
			Sleeper sleeper, Supplier<String> runIds, List<ExportTable> tables) {
		this.data = data;
		this.pseudonyms = pseudonyms;
		this.sink = sink;
		this.clock = clock;
		this.massDeleteGuardMinRows = massDeleteGuardMinRows;
		this.sleeper = sleeper;
		this.runIds = runIds;
		this.tables = tables;
	}

	public List<ExportTable> tables() {
		return tables;
	}

	public void initialize() {
		sink.initialize(tables);
	}

	public List<TableResult> run(Options options) {
		String runId = runIds.get();
		if (!options.dryRun()) {
			sink.prepare(tables);
		}
		List<TableResult> results = new ArrayList<>();
		for (ExportTable table : tables) {
			if (!options.onlyTables().isEmpty() && !options.onlyTables().contains(table.name())) {
				continue;
			}
			results.add(exportTable(table, options, runId));
		}
		return results;
	}

	/** Immediately removes one family's pseudonymous rows from every family table. */
	public Map<String, Long> purgeParent(String uid) {
		return sink.purgeFamily(pseudonyms.parent(uid));
	}

	private TableResult exportTable(ExportTable table, Options options, String runId) {
		Instant started = clock.instant();
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			try {
				return attemptTable(table, options, runId, started, attempt);
			} catch (TransientExportException | ProfileStorageException e) {
				log.warn("Export of {} failed on attempt {}/{}: {}", table.name(), attempt, MAX_ATTEMPTS, e.getClass().getSimpleName());
				if (attempt < MAX_ATTEMPTS) {
					pause(attempt);
					continue;
				}
				return failed(table, runId, started, attempt, e);
			} catch (RuntimeException e) {
				log.warn("Export of {} failed: {} ({})", table.name(), e.getClass().getSimpleName(), e.getMessage());
				return failed(table, runId, started, attempt, e);
			}
		}
		throw new IllegalStateException("unreachable");
	}

	private TableResult attemptTable(ExportTable table, Options options, String runId, Instant started, int attempt) {
		boolean incremental = table.mode() == ExportTable.Mode.INCREMENTAL;
		String previous = incremental && !options.dryRun() ? sink.lastWatermark(table.name()).orElse(null) : null;
		ExportTable.Extraction extraction = table.extractor()
				.extract(new ExportTable.Context(data, pseudonyms, clock, previous));
		List<Map<String, Object>> rows = extraction.rows().stream().map(table::seal).toList();
		String watermark = incremental ? (extraction.watermark() != null ? extraction.watermark() : previous) : null;

		if (options.dryRun()) {
			log.info("[dry run] {}: {} rows, columns {}", table.name(), rows.size(),
					table.allColumns().stream().map(Column::name).toList());
			return new TableResult(table.name(), "DRY_RUN", attempt, rows.size(), null, null, watermark, null);
		}

		sink.validateSchema(table);
		long before = sink.countRows(table);
		if (table.mode() == ExportTable.Mode.SNAPSHOT && !options.force() && before >= massDeleteGuardMinRows
				&& rows.size() < before / 2) {
			throw new MassDeleteException(table.name() + ": the source has " + rows.size() + " rows but the table has " + before
					+ "; refusing to delete more than half (use --force if this is intended)");
		}
		sink.apply(table, rows, runId);
		long after = sink.countRows(table);
		sink.recordRun(new RunRecord(runId, table.name(), table.mode().name(), TimePrecision.minute(started),
				TimePrecision.minute(clock.instant()), rows.size(), (long) rows.size(), before, after, "SUCCESS", attempt, null,
				watermark));
		log.info("Exported {}: {} rows read, table {} -> {} rows", table.name(), rows.size(), before, after);
		return new TableResult(table.name(), "SUCCESS", attempt, rows.size(), before, after, watermark, null);
	}

	private TableResult failed(ExportTable table, String runId, Instant started, int attempt, RuntimeException e) {
		String errorClass = e.getClass().getSimpleName();
		try {
			sink.recordRun(new RunRecord(runId, table.name(), table.mode().name(), TimePrecision.minute(started),
					TimePrecision.minute(clock.instant()), 0, null, null, null, "FAILED", attempt, errorClass, null));
		} catch (RuntimeException recordingFailed) {
			log.error("Could not record the failed run of {}", table.name());
		}
		return new TableResult(table.name(), "FAILED", attempt, 0, null, null, null, errorClass);
	}

	private void pause(int attempt) {
		try {
			sleeper.sleep(1000L << attempt);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}
	}
}
