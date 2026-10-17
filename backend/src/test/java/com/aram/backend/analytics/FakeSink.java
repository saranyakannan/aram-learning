package com.aram.backend.analytics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/** A BigQuery stand-in that keeps table contents in memory and applies MERGE semantics. */
class FakeSink implements BigQuerySink {

	final Map<String, Map<String, Map<String, Object>>> tables = new LinkedHashMap<>();
	final List<RunRecord> runs = new ArrayList<>();
	final List<String> calls = new ArrayList<>();
	final Map<String, List<String>> failures = new LinkedHashMap<>();
	Consumer<ExportTable> beforeApply = table -> { };
	String purged;

	private Map<String, Map<String, Object>> table(String name) {
		return tables.computeIfAbsent(name, key -> new LinkedHashMap<>());
	}

	void seedRows(String name, int count) {
		for (int i = 0; i < count; i++) {
			table(name).put("existing-" + i, Map.of("k", "existing-" + i));
		}
	}

	@Override
	public void initialize(List<ExportTable> definitions) {
		calls.add("initialize");
	}

	@Override
	public void prepare(List<ExportTable> definitions) {
		calls.add("prepare");
	}

	@Override
	public void validateSchema(ExportTable definition) {
		calls.add("validate:" + definition.name());
	}

	@Override
	public long countRows(ExportTable definition) {
		return table(definition.name()).size();
	}

	@Override
	public Optional<String> lastWatermark(String tableName) {
		return runs.stream().filter(run -> run.tableName().equals(tableName) && run.status().equals("SUCCESS") && run.watermark() != null)
				.reduce((first, second) -> second).map(RunRecord::watermark);
	}

	@Override
	public void apply(ExportTable definition, List<Map<String, Object>> rows, String runId) {
		calls.add("apply:" + definition.name());
		beforeApply.accept(definition);
		Map<String, Map<String, Object>> target = table(definition.name());
		Map<String, Map<String, Object>> incoming = new LinkedHashMap<>();
		for (Map<String, Object> row : rows) {
			StringBuilder key = new StringBuilder();
			definition.keyColumns().forEach(column -> key.append(row.get(column)).append('|'));
			incoming.put(key.toString(), row);
		}
		if (definition.mode() == ExportTable.Mode.SNAPSHOT) {
			target.keySet().retainAll(incoming.keySet());
		}
		target.putAll(incoming);
	}

	@Override
	public void recordRun(RunRecord record) {
		runs.add(record);
	}

	@Override
	public Map<String, Long> purgeFamily(String parentPseudonym) {
		purged = parentPseudonym;
		return Map.of();
	}
}
