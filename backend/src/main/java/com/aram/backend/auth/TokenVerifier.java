package com.aram.backend.auth;

/** Turns a bearer token into the signed-in parent. Implemented with Firebase; tests use fakes. */
public interface TokenVerifier {

	/** @throws UnauthenticatedException if the token is missing, malformed, expired or not ours */
	AuthenticatedParent verify(String idToken);
}
