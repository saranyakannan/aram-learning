package com.aram.backend.exception;

/** A previous message in the same conversation is still being answered. */
public class ConversationBusyException extends RuntimeException {

	public ConversationBusyException() {
		super("Aram is still answering your last message. Please wait a moment.");
	}
}
