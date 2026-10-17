package com.aram.backend.auth;

public class UnauthenticatedException extends RuntimeException {
	public UnauthenticatedException(String message, Throwable cause) {
		super(message, cause);
	}

	public UnauthenticatedException(String message) {
		super(message);
	}
}
