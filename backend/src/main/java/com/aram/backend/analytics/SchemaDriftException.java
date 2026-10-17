package com.aram.backend.analytics;

/** The live BigQuery table no longer matches the definition in code; the export stops before writing anything. */
public class SchemaDriftException extends RuntimeException {

	public SchemaDriftException(String message) {
		super(message);
	}
}
