package com.aram.backend.analytics;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

/**
 * Replaces Firebase uids and child ids with keyed pseudonyms before anything reaches BigQuery. HMAC-SHA256 with a secret
 * key (not a plain hash), so the ids cannot be recovered by hashing a list of known uids. Parent and child ids use
 * different prefixes so the same string never maps to the same pseudonym for both. Rotating the key changes every
 * pseudonym; the next snapshot export rewrites them all and removes the old rows.
 */
public final class Pseudonymizer {

	static final int MIN_KEY_LENGTH = 32;
	private static final int OUTPUT_HEX_CHARS = 32;

	private final byte[] key;

	/** Fails closed: there is no default key, and a short one is refused. */
	public Pseudonymizer(String secret) {
		if (secret == null || secret.strip().length() < MIN_KEY_LENGTH) {
			throw new IllegalStateException("The analytics pseudonym key (ANALYTICS_HMAC_KEY) is missing or shorter than "
					+ MIN_KEY_LENGTH + " characters");
		}
		this.key = secret.strip().getBytes(StandardCharsets.UTF_8);
	}

	public String parent(String uid) {
		return pseudonym("parent:", uid);
	}

	public String child(String childId) {
		return pseudonym("child:", childId);
	}

	private String pseudonym(String prefix, String id) {
		if (id == null || id.isBlank()) {
			throw new IllegalArgumentException("Cannot pseudonymise an empty id");
		}
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(key, "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal((prefix + id).getBytes(StandardCharsets.UTF_8)))
					.substring(0, OUTPUT_HEX_CHARS);
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}
}
