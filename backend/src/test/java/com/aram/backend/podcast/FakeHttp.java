package com.aram.backend.podcast;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A stand-in for the network: answers from a map of address to body and remembers what was asked for. */
class FakeHttp implements HttpFetcher {

	final Map<String, Response> answers = new LinkedHashMap<>();
	final List<String> requested = new ArrayList<>();

	void answer(String url, String body) {
		answers.put(url, new Response(200, body.getBytes(StandardCharsets.UTF_8), false, "text/plain"));
	}

	void answer(String url, int status, String body) {
		answers.put(url, new Response(status, body.getBytes(StandardCharsets.UTF_8), false, "text/plain"));
	}

	@Override
	public Response get(URI uri, int maxBytes) {
		requested.add(uri.toString());
		Response response = answers.get(uri.toString());
		if (response == null) {
			throw new com.aram.backend.exception.PodcastLookupException("No answer set for " + uri);
		}
		return response;
	}
}
