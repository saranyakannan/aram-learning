package com.aram.backend.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Entry point of the Cloud Run Job (and of local runs). Only active with {@code ARAM_EXPORT_ENABLED=true}; it runs once
 * and exits with a non-zero code if any table failed, so Cloud Run marks the execution as failed.
 *
 * <p>Options: {@code --dry-run} (read Firestore and print counts; writes nothing), {@code --force} (allow a snapshot to
 * remove more than half of a table), {@code --tables=a,b}, {@code --init-only} (create/validate the dataset, tables and
 * views), {@code --purge-parent=<firebase uid>} (delete that family's pseudonymous rows everywhere).
 */
@Component
@ConditionalOnProperty(prefix = "aram.export", name = "enabled", havingValue = "true")
public class ExportJob implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(ExportJob.class);

	private final ExportService service;
	private final ApplicationContext context;

	public ExportJob(ExportService service, ApplicationContext context) {
		this.service = service;
		this.context = context;
	}

	@Override
	public void run(ApplicationArguments args) {
		int code;
		try {
			code = execute(args);
		} catch (RuntimeException e) {
			log.error("Export job failed: {}", e.getClass().getSimpleName(), e);
			code = 1;
		}
		int exitCode = code;
		System.exit(SpringApplication.exit(context, () -> exitCode));
	}

	int execute(ApplicationArguments args) {
		if (args.containsOption("init-only")) {
			service.initialize();
			log.info("Analytics dataset, tables and views are ready");
			return 0;
		}
		List<String> purge = args.getOptionValues("purge-parent");
		if (purge != null && !purge.isEmpty()) {
			Map<String, Long> deleted = service.purgeParent(purge.get(0));
			log.info("Purged one family: rows deleted per table {}", deleted);
			return 0;
		}
		Set<String> only = new HashSet<>();
		List<String> tables = args.getOptionValues("tables");
		if (tables != null) {
			for (String value : tables) {
				for (String name : value.split(",")) {
					if (!name.isBlank()) {
						only.add(name.strip());
					}
				}
			}
		}
		List<ExportService.TableResult> results = service.run(
				new ExportService.Options(args.containsOption("dry-run"), args.containsOption("force"), only));
		results.forEach(result -> log.info("{} {} (attempts {}, rows read {}, before {}, after {}{})", result.table(),
				result.status(), result.attempts(), result.rowsRead(), result.rowsBefore(), result.rowsAfter(),
				result.errorClass() == null ? "" : ", " + result.errorClass()));
		return results.stream().anyMatch(result -> result.status().equals("FAILED")) ? 1 : 0;
	}
}
