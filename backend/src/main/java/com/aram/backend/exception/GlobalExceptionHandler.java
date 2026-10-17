package com.aram.backend.exception;

import com.aram.backend.agent.ModelException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(ChannelNotFoundException.class)
	public ResponseEntity<Object> handleNotFound(ChannelNotFoundException ex) {
		return body(HttpStatus.NOT_FOUND, ex.getMessage());
	}

	@ExceptionHandler(ConversationNotFoundException.class)
	public ResponseEntity<Object> handleConversationNotFound(ConversationNotFoundException ex) {
		return body(HttpStatus.NOT_FOUND, ex.getMessage());
	}

	@ExceptionHandler(ConversationBusyException.class)
	public ResponseEntity<Object> handleConversationBusy(ConversationBusyException ex) {
		return body(HttpStatus.CONFLICT, ex.getMessage());
	}

	@ExceptionHandler(InvalidRequestException.class)
	public ResponseEntity<Object> handleInvalidRequest(InvalidRequestException ex) {
		return body(HttpStatus.BAD_REQUEST, ex.getMessage());
	}

	@ExceptionHandler(RateLimitException.class)
	public ResponseEntity<Object> handleRateLimit(RateLimitException ex) {
		return body(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
	}

	@ExceptionHandler(ModelException.class)
	public ResponseEntity<Object> handleModelError(ModelException ex) {
		log.error("Model error", ex);
		return body(HttpStatus.BAD_GATEWAY, "The assistant isn't available right now. Please try again in a moment.");
	}

	@ExceptionHandler(ProfileStorageException.class)
	public ResponseEntity<Object> handleProfileStorage(ProfileStorageException ex) {
		log.error("Profile storage error", ex);
		return body(HttpStatus.SERVICE_UNAVAILABLE, "Profile storage is unavailable right now");
	}

	@ExceptionHandler(YoutubeIntegrationException.class)
	public ResponseEntity<Object> handleYoutubeError(YoutubeIntegrationException ex) {
		log.error("YouTube integration error", ex);
		return body(HttpStatus.BAD_GATEWAY, ex.getMessage());
	}

	@ExceptionHandler(PodcastLookupException.class)
	public ResponseEntity<Object> handlePodcastLookup(PodcastLookupException ex) {
		log.warn("Podcast lookup failed: {}", ex.getMessage());
		return body(HttpStatus.BAD_GATEWAY, "I couldn't reach that podcast right now. Please try again in a moment.");
	}

	private ResponseEntity<Object> body(HttpStatus status, String message) {
		return ResponseEntity.status(status).body(Map.of(
				"timestamp", Instant.now().toString(),
				"status", status.value(),
				"error", status.getReasonPhrase(),
				"message", message
		));
	}
}
