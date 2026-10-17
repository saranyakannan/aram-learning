package com.aram.backend.analytics;

/** A failure worth retrying (rate limit, backend error, timeout). Anything else fails the table straight away. */
public class TransientExportException extends RuntimeException {

	public TransientExportException(String message, Throwable cause) {
		super(message, cause);
	}
}
