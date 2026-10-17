package com.aram.backend.analytics;

import java.time.Instant;

/** One row of {@code ops_export_runs}: counts and status only, never row contents or raw error text. */
public record RunRecord(
		String runId,
		String tableName,
		String mode,
		Instant startedAt,
		Instant finishedAt,
		long rowsRead,
		Long rowsLoaded,
		Long rowsBefore,
		Long rowsAfter,
		String status,
		int attempt,
		String errorClass,
		String watermark
) {
}
