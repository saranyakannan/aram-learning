package com.aram.backend.analytics;

/** A snapshot would remove more than half of a table's rows; refused unless the run is forced. */
public class MassDeleteException extends RuntimeException {

	public MassDeleteException(String message) {
		super(message);
	}
}
