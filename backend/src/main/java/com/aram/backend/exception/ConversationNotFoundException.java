package com.aram.backend.exception;

/** The conversation does not exist, has expired, or belongs to another parent (the three cases look the same on purpose). */
public class ConversationNotFoundException extends RuntimeException {

	public ConversationNotFoundException() {
		super("That conversation wasn't found. Please start a new chat.");
	}
}
