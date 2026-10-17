package com.aram.backend.exception;

/** A podcast directory or feed could not be read. The message is safe to show a parent. */
public class PodcastLookupException extends RuntimeException {

	public PodcastLookupException(String message) {
		super(message);
	}

	public PodcastLookupException(String message, Throwable cause) {
		super(message, cause);
	}
}
