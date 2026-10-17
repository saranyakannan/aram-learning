package com.aram.backend.analytics;

import com.aram.backend.catalog.Collections;
import com.aram.backend.exception.ProfileStorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static com.aram.backend.analytics.FakeExportData.map;
import static com.aram.backend.analytics.FakeExportData.ts;
import static org.assertj.core.api.Assertions.assertThat;

class ExportServiceTest {

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-14T12:34:56Z"), ZoneOffset.UTC);

	private FakeExportData data;
	private FakeSink sink;
	private final List<Long> pauses = new ArrayList<>();
	private ExportService service;

	@BeforeEach
	void setUp() {
		data = new FakeExportData();
		sink = new FakeSink();
		service = serviceFor(ExportTables.all());
		data.put(Collections.CATEGORIES, "math-counting", map("name", "Math", "sortOrder", 1L, "isActive", true));
		data.put(Collections.PARENTS, "parent-uid-0000000000000000001", map("accountStatus", "ACTIVE", "createdAt", ts("2026-10-01T00:00:00Z")));
		data.put(Collections.CONTENT_ASSESSMENTS, "a1", map("targetType", "CONTENT_SOURCE", "targetId", "s1", "assessorType", "LLM",
				"assessmentStatus", "ASSESSED", "assessedAt", ts("2026-10-10T08:30:00Z")));
	}

	private ExportService serviceFor(List<ExportTable> tables) {
		AtomicInteger runNumber = new AtomicInteger();
		return new ExportService(data, new Pseudonymizer("s".repeat(40)), sink, CLOCK, 10, pauses::add,
				() -> "run-" + runNumber.incrementAndGet(), tables);
	}

	private ExportService.TableResult result(List<ExportService.TableResult> results, String table) {
		return results.stream().filter(r -> r.table().equals(table)).findFirst().orElseThrow();
	}

	@Test
	void aRunExportsEveryTableAndRecordsCountsWithoutRowContent() {
		List<ExportService.TableResult> results = service.run(ExportService.Options.defaults());

		assertThat(results).hasSize(ExportTables.all().size()).allMatch(r -> r.status().equals("SUCCESS"));
		assertThat(sink.calls.get(0)).as("a run only prepares tables; views are administrator set-up").isEqualTo("prepare");
		assertThat(sink.calls).doesNotContain("initialize");
		assertThat(sink.runs).hasSize(ExportTables.all().size()).allMatch(run -> run.status().equals("SUCCESS") && run.errorClass() == null);
		RunRecord categories = sink.runs.stream().filter(r -> r.tableName().equals("dim_categories")).findFirst().orElseThrow();
		assertThat(categories.rowsRead()).isEqualTo(1);
		assertThat(categories.rowsAfter()).isEqualTo(1);
		assertThat(categories.startedAt()).isEqualTo(Instant.parse("2026-10-14T12:34:00Z"));
		assertThat(sink.calls).containsSubsequence("validate:dim_categories", "apply:dim_categories");
	}

	@Test
	void theCheckpointOfAnIncrementalTableAdvancesOnlyAfterASuccessfulRun() {
		service.run(ExportService.Options.defaults());
		String first = sink.lastWatermark("fact_content_assessments").orElseThrow();
		assertThat(first).isEqualTo("2026-10-10T08:30:00Z");

		data.put(Collections.CONTENT_ASSESSMENTS, "a2", map("targetType", "CONTENT_SOURCE", "targetId", "s2", "assessorType", "LLM",
				"assessmentStatus", "ASSESSED", "assessedAt", ts("2026-10-14T09:15:00Z")));
		service.run(ExportService.Options.defaults());

		assertThat(sink.lastWatermark("fact_content_assessments")).contains("2026-10-14T09:15:00Z");
		assertThat(sink.tables.get("fact_content_assessments")).hasSize(2);
	}

	@Test
	void aFailedRunDoesNotMoveTheCheckpoint() {
		service.run(ExportService.Options.defaults());
		data.put(Collections.CONTENT_ASSESSMENTS, "a2", map("targetType", "CONTENT_SOURCE", "targetId", "s2", "assessorType", "LLM",
				"assessmentStatus", "ASSESSED", "assessedAt", ts("2026-10-14T09:15:00Z")));
		sink.beforeApply = table -> {
			if (table.name().equals("fact_content_assessments")) {
				throw new SchemaDriftException("boom");
			}
		};

		List<ExportService.TableResult> results = service.run(ExportService.Options.defaults());

		assertThat(result(results, "fact_content_assessments").status()).isEqualTo("FAILED");
		assertThat(sink.lastWatermark("fact_content_assessments")).contains("2026-10-10T08:30:00Z");
	}

	@Test
	void aSecondRunWithUnchangedDataChangesNothing() {
		service.run(ExportService.Options.defaults());
		Map<String, Integer> sizes = new java.util.LinkedHashMap<>();
		sink.tables.forEach((name, rows) -> sizes.put(name, rows.size()));
		var copy = new java.util.LinkedHashMap<>(sink.tables.get("dim_parents"));

		service.run(ExportService.Options.defaults());

		sink.tables.forEach((name, rows) -> assertThat(rows).as(name).hasSize(sizes.get(name)));
		assertThat(sink.tables.get("dim_parents")).isEqualTo(copy);
	}

	@Test
	void aDeletionInFirestoreDisappearsFromASnapshotTableButNotFromAppendOnlyHistory() {
		service.run(ExportService.Options.defaults());
		assertThat(sink.tables.get("dim_parents")).hasSize(1);
		data.collections.get(Collections.PARENTS).clear();
		data.collections.get(Collections.CONTENT_ASSESSMENTS).clear();

		service.run(ExportService.Options.defaults());

		assertThat(sink.tables.get("dim_parents")).isEmpty();
		assertThat(sink.tables.get("fact_content_assessments")).hasSize(1);
	}

	@Test
	void oneTableFailingDoesNotStopTheOthersAndOnlyTheErrorClassIsRecorded() {
		sink.beforeApply = table -> {
			if (table.name().equals("dim_tags")) {
				throw new IllegalStateException("secret detail that must not be recorded");
			}
		};

		List<ExportService.TableResult> results = service.run(ExportService.Options.defaults());

		assertThat(result(results, "dim_tags").status()).isEqualTo("FAILED");
		assertThat(result(results, "dim_tags").errorClass()).isEqualTo("IllegalStateException");
		assertThat(results.stream().filter(r -> r.status().equals("SUCCESS"))).hasSize(ExportTables.all().size() - 1);
		RunRecord failed = sink.runs.stream().filter(r -> r.status().equals("FAILED")).findFirst().orElseThrow();
		assertThat(failed.errorClass()).isEqualTo("IllegalStateException");
		assertThat(failed.toString()).doesNotContain("secret detail");
	}

	@Test
	void transientFailuresAreRetriedWithBackoffAndThenSucceed() {
		AtomicInteger failures = new AtomicInteger(2);
		sink.beforeApply = table -> {
			if (table.name().equals("dim_categories") && failures.getAndDecrement() > 0) {
				throw new TransientExportException("rate limited", null);
			}
		};

		ExportService.TableResult categories = result(service.run(ExportService.Options.defaults()), "dim_categories");

		assertThat(categories.status()).isEqualTo("SUCCESS");
		assertThat(categories.attempts()).isEqualTo(3);
		assertThat(pauses).containsExactly(2000L, 4000L);
	}

	@Test
	void aFirestoreOutageIsRetriedLikeABigQueryOne() {
		AtomicInteger failures = new AtomicInteger(1);
		FakeExportData flaky = new FakeExportData() {
			@Override
			public List<com.aram.backend.catalog.FirestoreOps.Doc> all(String collection) {
				if (collection.equals(Collections.CATEGORIES) && failures.getAndDecrement() > 0) {
					throw new ProfileStorageException("down", null);
				}
				return super.all(collection);
			}
		};
		flaky.collections.putAll(data.collections);
		data = flaky;
		service = serviceFor(ExportTables.all());

		assertThat(result(service.run(ExportService.Options.defaults()), "dim_categories").attempts()).isEqualTo(2);
	}

	@Test
	void afterThreeTransientFailuresTheTableIsReportedFailedNotRetriedForever() {
		sink.beforeApply = table -> {
			if (table.name().equals("dim_categories")) {
				throw new TransientExportException("still down", null);
			}
		};

		ExportService.TableResult categories = result(service.run(ExportService.Options.defaults()), "dim_categories");

		assertThat(categories.status()).isEqualTo("FAILED");
		assertThat(categories.attempts()).isEqualTo(ExportService.MAX_ATTEMPTS);
		assertThat(categories.errorClass()).isEqualTo("TransientExportException");
	}

	@Test
	void aSnapshotThatWouldRemoveMoreThanHalfOfALargeTableIsRefusedUnlessForced() {
		sink.seedRows("dim_categories", 20);

		ExportService.TableResult refused = result(service.run(ExportService.Options.defaults()), "dim_categories");

		assertThat(refused.status()).isEqualTo("FAILED");
		assertThat(refused.errorClass()).isEqualTo("MassDeleteException");
		assertThat(sink.tables.get("dim_categories")).hasSize(20);

		ExportService.TableResult forced = result(service.run(new ExportService.Options(false, true, Set.of())), "dim_categories");
		assertThat(forced.status()).isEqualTo("SUCCESS");
		assertThat(sink.tables.get("dim_categories")).hasSize(1);
	}

	@Test
	void aSmallTableCanLegitimatelyBecomeEmpty() {
		data.collections.get(Collections.PARENTS).clear();
		sink.seedRows("dim_parents", 3);

		assertThat(result(service.run(ExportService.Options.defaults()), "dim_parents").status()).isEqualTo("SUCCESS");
		assertThat(sink.tables.get("dim_parents")).isEmpty();
	}

	@Test
	void aSchemaMismatchStopsTheTableBeforeAnythingIsWritten() {
		FakeSink drifting = new FakeSink() {
			@Override
			public void validateSchema(ExportTable definition) {
				if (definition.name().equals("dim_tags")) {
					throw new SchemaDriftException("column 3 is 'x'");
				}
			}
		};
		sink = drifting;
		service = serviceFor(ExportTables.all());

		List<ExportService.TableResult> results = service.run(ExportService.Options.defaults());

		assertThat(result(results, "dim_tags").errorClass()).isEqualTo("SchemaDriftException");
		assertThat(drifting.calls).doesNotContain("apply:dim_tags");
	}

	@Test
	void aDryRunReadsFirestoreButNeverTouchesBigQuery() {
		List<ExportService.TableResult> results = service.run(new ExportService.Options(true, false, Set.of()));

		assertThat(results).allMatch(r -> r.status().equals("DRY_RUN"));
		assertThat(result(results, "dim_categories").rowsRead()).isEqualTo(1);
		assertThat(sink.calls).isEmpty();
		assertThat(sink.runs).isEmpty();
		assertThat(sink.tables).isEmpty();
	}

	@Test
	void onlyTheRequestedTablesAreExported() {
		List<ExportService.TableResult> results = service.run(new ExportService.Options(false, false, Set.of("dim_tags", "dim_parents")));

		assertThat(results).extracting(ExportService.TableResult::table).containsExactly("dim_tags", "dim_parents");
	}

	@Test
	void purgingAFamilyPassesOnlyThePseudonymNeverTheUid() {
		String uid = "parent-uid-0000000000000000001";

		service.purgeParent(uid);

		assertThat(sink.purged).isEqualTo(new Pseudonymizer("s".repeat(40)).parent(uid)).doesNotContain(uid);
	}
}
