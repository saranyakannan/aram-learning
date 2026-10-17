package com.aram.backend.podcast;

import com.aram.backend.exception.PodcastLookupException;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Feed addresses come from a directory anyone can publish to, so internal addresses must never be fetched. */
class SafeHttpTest {

	private static InetAddress ip(String literal) throws Exception {
		return InetAddress.getByName(literal);
	}

	@Test
	void onlyPublicInternetAddressesAreAllowed() throws Exception {
		for (String internal : new String[] { "127.0.0.1", "10.1.2.3", "172.16.0.5", "192.168.1.1", "169.254.169.254",
				"0.0.0.0", "100.64.0.1", "198.18.0.1", "224.0.0.1", "::1", "fe80::1", "fc00::1", "fd12:3456::1" }) {
			assertThat(SafeHttp.isPublic(ip(internal))).as(internal).isFalse();
		}
		for (String external : new String[] { "8.8.8.8", "93.184.216.34", "2606:4700:4700::1111" }) {
			assertThat(SafeHttp.isPublic(ip(external))).as(external).isTrue();
		}
	}

	@Test
	void anAddressThatPointsInsideIsRefusedBeforeAnyRequestIsMade() {
		for (String url : new String[] { "http://127.0.0.1/feed", "http://localhost/feed", "http://169.254.169.254/latest/meta-data",
				"https://10.0.0.8:8443/x", "http://[::1]/feed" }) {
			assertThatThrownBy(() -> SafeHttp.requirePublic(URI.create(url))).as(url).isInstanceOf(PodcastLookupException.class);
		}
	}

	@Test
	void onlyHttpAndHttpsWithNoEmbeddedCredentialsAreAllowed() {
		for (String url : new String[] { "file:///etc/passwd", "ftp://example.com/x", "gopher://8.8.8.8/", "http://user:pw@8.8.8.8/" }) {
			assertThatThrownBy(() -> SafeHttp.requirePublic(URI.create(url))).as(url).isInstanceOf(PodcastLookupException.class);
		}
	}

	@Test
	void aPublicLiteralAddressPasses() {
		SafeHttp.requirePublic(URI.create("https://8.8.8.8/feed.xml"));
	}
}
