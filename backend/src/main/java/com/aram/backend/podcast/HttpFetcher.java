package com.aram.backend.podcast;

import java.net.URI;

/** Fetches a web page; the real one is {@link SafeHttp}, tests use a fake. */
public interface HttpFetcher {

	/** {@code body} holds at most the requested number of bytes ({@code truncated} says whether there was more). */
	record Response(int status, byte[] body, boolean truncated, String contentType) {
	}

	Response get(URI uri, int maxBytes);
}
