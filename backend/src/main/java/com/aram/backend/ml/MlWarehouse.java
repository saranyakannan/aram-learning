package com.aram.backend.ml;

import java.util.List;
import java.util.Map;

/** The two things the recommendation job needs from BigQuery, so the pipeline can be tested without it. */
public interface MlWarehouse {

	/** Runs a statement (DDL or CREATE TABLE AS) and waits for it. */
	void execute(String sql);

	/**
	 * Runs a statement with named array parameters (values are {@code String} or {@code Long} lists) and waits for it. Used where
	 * text must reach BigQuery without being pasted into the SQL.
	 */
	default void execute(String sql, Map<String, List<?>> arrayParameters) {
		throw new UnsupportedOperationException("This warehouse does not take query parameters");
	}

	/** Runs a query; every value comes back as text (null stays null). */
	List<Map<String, String>> query(String sql);
}
