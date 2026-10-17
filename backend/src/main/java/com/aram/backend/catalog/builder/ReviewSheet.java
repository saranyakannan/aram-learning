package com.aram.backend.catalog.builder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The review spreadsheet: plain CSV so it opens in any spreadsheet. The first block of columns describes the candidate (public catalog
 * data only); the last block is where the reviewer decides. Candidate text comes from third parties, so on the way out any cell that a
 * spreadsheet would run as a formula is neutralised.
 */
public final class ReviewSheet {

	public static final List<String> INFO_COLUMNS = List.of("source_id", "kind", "name", "link", "subscribers", "made_for_kids", "status",
			"model_verdict", "model_confidence", "suggested_min_age_years", "suggested_max_age_years", "suggested_tags", "reason", "concerns",
			"lookalike_of", "found_by");
	public static final List<String> DECISION_COLUMNS = List.of("decision", "final_tags", "final_min_age_years", "final_max_age_years", "notes");
	public static final List<String> COLUMNS;

	static {
		List<String> all = new ArrayList<>(INFO_COLUMNS);
		all.addAll(DECISION_COLUMNS);
		COLUMNS = List.copyOf(all);
	}

	private ReviewSheet() {
	}

	public static String toCsv(List<Map<String, String>> rows) {
		StringBuilder csv = new StringBuilder(String.join(",", COLUMNS)).append("\r\n");
		for (Map<String, String> row : rows) {
			List<String> cells = new ArrayList<>();
			for (String column : COLUMNS) {
				String value = row.getOrDefault(column, "");
				cells.add(escape(INFO_COLUMNS.contains(column) ? neutralise(value) : value));
			}
			csv.append(String.join(",", cells)).append("\r\n");
		}
		return csv.toString();
	}

	/** A cell starting with = + - @ (or a tab or carriage return) could run as a formula; a leading apostrophe makes it plain text. */
	static String neutralise(String value) {
		if (value != null && !value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0) {
			return "'" + value;
		}
		return value == null ? "" : value;
	}

	private static String escape(String value) {
		return value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")
				? "\"" + value.replace("\"", "\"\"") + "\"" : value;
	}

	/** Reads a CSV (quotes, commas and line breaks inside quoted cells are handled) into rows keyed by the header names. */
	public static List<Map<String, String>> parse(String csv) {
		List<List<String>> records = records(csv);
		if (records.isEmpty()) {
			return List.of();
		}
		List<String> header = records.get(0).stream().map(String::strip).toList();
		List<Map<String, String>> rows = new ArrayList<>();
		for (List<String> record : records.subList(1, records.size())) {
			if (record.stream().allMatch(String::isBlank)) {
				continue;
			}
			Map<String, String> row = new LinkedHashMap<>();
			for (int i = 0; i < header.size(); i++) {
				row.put(header.get(i), i < record.size() ? record.get(i).strip() : "");
			}
			rows.add(row);
		}
		return rows;
	}

	private static List<List<String>> records(String csv) {
		List<List<String>> records = new ArrayList<>();
		List<String> record = new ArrayList<>();
		StringBuilder cell = new StringBuilder();
		boolean quoted = false;
		String text = csv.startsWith("﻿") ? csv.substring(1) : csv;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (quoted) {
				if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
					cell.append('"');
					i++;
				} else if (c == '"') {
					quoted = false;
				} else {
					cell.append(c);
				}
			} else if (c == '"') {
				quoted = true;
			} else if (c == ',') {
				record.add(cell.toString());
				cell.setLength(0);
			} else if (c == '\n' || c == '\r') {
				if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
					i++;
				}
				record.add(cell.toString());
				cell.setLength(0);
				records.add(record);
				record = new ArrayList<>();
			} else {
				cell.append(c);
			}
		}
		if (cell.length() > 0 || !record.isEmpty()) {
			record.add(cell.toString());
			records.add(record);
		}
		return records;
	}
}
