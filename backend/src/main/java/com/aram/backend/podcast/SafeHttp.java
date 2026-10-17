package com.aram.backend.podcast;

import com.aram.backend.config.AramProperties;
import com.aram.backend.exception.PodcastLookupException;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Downloads feeds and directory answers for podcasts. Feed addresses come from a public directory that anyone can
 * publish to, so every address (and every redirect) is checked before it is fetched: only http(s), and never a
 * loopback, private, link-local or otherwise internal address. Bodies are size-capped and requests time out.
 *
 * <p>Known limit: the address is checked and then the HTTP client resolves the name again, so a hostile DNS server could
 * answer differently the second time. Run the backend without access to internal networks it should not reach.
 */
@Component
public class SafeHttp implements HttpFetcher {

	private static final int MAX_REDIRECTS = 4;

	private final HttpClient client;
	private final Duration timeout;

	public SafeHttp(AramProperties properties) {
		this.timeout = Duration.ofMillis(properties.getPodcast().getTimeoutMillis());
		this.client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(timeout).build();
	}

	@Override
	public Response get(URI uri, int maxBytes) {
		URI current = uri;
		try {
			for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
				requirePublic(current);
				HttpResponse<InputStream> response = client.send(
						HttpRequest.newBuilder(current).timeout(timeout)
								.header("User-Agent", "Aram/1.0 (parent-controlled kids app)")
								.header("Accept", "application/rss+xml, application/xml, text/xml, application/json, */*;q=0.5")
								.GET().build(),
						HttpResponse.BodyHandlers.ofInputStream());
				int status = response.statusCode();
				if (status >= 300 && status < 400) {
					String location = response.headers().firstValue("Location").orElse(null);
					response.body().close();
					if (location == null) {
						throw new PodcastLookupException("A redirect had no destination");
					}
					current = current.resolve(location);
					continue;
				}
				try (InputStream body = response.body()) {
					ByteArrayOutputStream out = new ByteArrayOutputStream();
					byte[] buffer = new byte[16 * 1024];
					boolean truncated = false;
					int read;
					while ((read = body.read(buffer)) != -1) {
						int room = maxBytes - out.size();
						if (read > room) {
							out.write(buffer, 0, Math.max(room, 0));
							truncated = true;
							break;
						}
						out.write(buffer, 0, read);
					}
					return new Response(status, out.toByteArray(), truncated,
							response.headers().firstValue("Content-Type").orElse(""));
				}
			}
			throw new PodcastLookupException("Too many redirects");
		} catch (IOException e) {
			throw new PodcastLookupException("Could not reach " + uri.getHost(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new PodcastLookupException("Interrupted while fetching " + uri.getHost(), e);
		}
	}

	static void requirePublic(URI uri) {
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
		if (!scheme.equals("http") && !scheme.equals("https")) {
			throw new PodcastLookupException("Only http and https addresses are allowed");
		}
		if (uri.getHost() == null || uri.getUserInfo() != null) {
			throw new PodcastLookupException("That address isn't allowed");
		}
		try {
			for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
				if (!isPublic(address)) {
					throw new PodcastLookupException("That address isn't allowed");
				}
			}
		} catch (UnknownHostException e) {
			throw new PodcastLookupException("Could not find " + uri.getHost(), e);
		}
	}

	/** True only for addresses on the public internet. */
	static boolean isPublic(InetAddress address) {
		if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
				|| address.isSiteLocalAddress() || address.isMulticastAddress()) {
			return false;
		}
		byte[] bytes = address.getAddress();
		if (bytes.length == 4) {
			int first = bytes[0] & 0xff;
			int second = bytes[1] & 0xff;
			boolean carrierGradeNat = first == 100 && second >= 64 && second <= 127;
			boolean thisNetwork = first == 0;
			boolean benchmark = first == 198 && (second == 18 || second == 19);
			return !(carrierGradeNat || thisNetwork || benchmark);
		}
		boolean uniqueLocal = (bytes[0] & 0xfe) == 0xfc;
		return !uniqueLocal;
	}
}
