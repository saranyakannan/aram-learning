package com.aram.backend.catalog.builder;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewSheetTest {

	private static Map<String, String> row(String... kv) {
		Map<String, String> row = new LinkedHashMap<>();
		for (int i = 0; i < kv.length; i += 2) {
			row.put(kv[i], kv[i + 1]);
		}
		return row;
	}

	@Test
	void aSheetSurvivesAWriteAndReadWithCommasQuotesAndLineBreaks() {
		Map<String, String> original = row("source_id", "youtube_UCa", "name", "Rhymes, \"Songs\" & more", "reason", "Line one\nLine two", "decision", "approve",
				"final_tags", "music;emotions", "notes", "ok, good");

		List<Map<String, String>> back = ReviewSheet.parse(ReviewSheet.toCsv(List.of(original)));

		assertThat(back).hasSize(1);
		assertThat(back.get(0)).containsEntry("name", "Rhymes, \"Songs\" & more").containsEntry("reason", "Line one\nLine two")
				.containsEntry("decision", "approve").containsEntry("final_tags", "music;emotions").containsEntry("notes", "ok, good");
	}

	@Test
	void textThatASpreadsheetWouldRunAsAFormulaIsNeutralisedInTheDescriptiveColumnsOnly() {
		String csv = ReviewSheet.toCsv(List.of(row("name", "=HYPERLINK(\"http://evil\",\"x\")", "reason", "+1+1", "concerns", "@SUM(A1)", "link", "-2+3",
				"notes", "=my own note")));

		List<Map<String, String>> back = ReviewSheet.parse(csv);

		assertThat(back.get(0).get("name")).startsWith("'=");
		assertThat(back.get(0).get("reason")).startsWith("'+");
		assertThat(back.get(0).get("concerns")).startsWith("'@");
		assertThat(back.get(0).get("link")).startsWith("'-");
		assertThat(back.get(0).get("notes")).as("the reviewer's own column is left as typed").isEqualTo("=my own note");
	}

	@Test
	void theHeaderDecidesTheColumnsSoReorderedOrExtraColumnsAndExcelStyleFilesStillWork() {
		String csv = "﻿decision,source_id,extra\r\napprove,youtube_UCa,x\r\n\r\n,youtube_UCb\r\nreject,youtube_UCc,y\r\n";

		List<Map<String, String>> rows = ReviewSheet.parse(csv);

		assertThat(rows).hasSize(3);
		assertThat(rows.get(0)).containsEntry("source_id", "youtube_UCa").containsEntry("decision", "approve");
		assertThat(rows.get(1)).containsEntry("source_id", "youtube_UCb").containsEntry("decision", "");
		assertThat(rows.get(2)).containsEntry("decision", "reject");
	}

	@Test
	void theWrittenHeaderListsEveryDescriptiveColumnThenTheDecisionColumns() {
		String header = ReviewSheet.toCsv(List.of()).split("\r\n")[0];

		assertThat(header).startsWith("source_id,kind,name,link").endsWith("decision,final_tags,final_min_age_years,final_max_age_years,notes");
		assertThat(ReviewSheet.parse("")).isEmpty();
	}
}
