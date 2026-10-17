package com.aram.backend.agent;

public class ModelException extends RuntimeException {
	public ModelException(String message, Throwable cause) {
		super(message, cause);
	}

	public ModelException(String message) {
		super(message);
	}
}
