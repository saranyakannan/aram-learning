package com.aram.backend.ml;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Entry point of the recommendation batch; active only with {@code ARAM_ML_ENABLED=true}. Runs once and exits.
 *
 * <p>Options: {@code --setup} (create the ML dataset and embedding model), {@code --dry-run} (print the SQL, run nothing),
 * {@code --write-back-only} (cluster the existing embeddings and write {@code source_signals}); with no option it runs
 * refresh then write-back.
 */
@Component
@ConditionalOnProperty(prefix = "aram.ml", name = "enabled", havingValue = "true")
public class MlJob implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(MlJob.class);

	private final MlPipelineService pipeline;
	private final ApplicationContext context;

	public MlJob(MlPipelineService pipeline, ApplicationContext context) {
		this.pipeline = pipeline;
		this.context = context;
	}

	@Override
	public void run(ApplicationArguments args) {
		int code;
		try {
			code = execute(args);
		} catch (RuntimeException e) {
			log.error("Recommendation job failed: {}", e.getClass().getSimpleName(), e);
			code = 1;
		}
		int exitCode = code;
		System.exit(SpringApplication.exit(context, () -> exitCode));
	}

	int execute(ApplicationArguments args) {
		if (args.containsOption("dry-run")) {
			pipeline.plan().forEach(sql -> log.info("Would run:\n{}", sql));
			return 0;
		}
		if (args.containsOption("setup")) {
			pipeline.setup();
			log.info("Recommendation dataset and embedding model are ready");
			return 0;
		}
		if (!args.containsOption("write-back-only")) {
			log.info("Embedded {} sources", pipeline.refresh());
		}
		var result = pipeline.writeBack();
		log.info("Signals written: {}, removed: {}, clusters: {}", result.signalsWritten(), result.signalsRemoved(), result.clusters());
		return 0;
	}
}
