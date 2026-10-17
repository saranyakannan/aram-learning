package com.aram.backend.auth;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Verifies Firebase Authentication ID tokens. When {@code FIREBASE_AUTH_EMULATOR_HOST} is set the Admin SDK
 * accepts the local emulator's tokens instead, which is how sign-in is tested without a real account.
 */
@Component
public class FirebaseTokenVerifier implements TokenVerifier {

	private final ObjectProvider<FirebaseAuth> firebaseAuth;

	public FirebaseTokenVerifier(ObjectProvider<FirebaseAuth> firebaseAuth) {
		this.firebaseAuth = firebaseAuth;
	}

	@Override
	public AuthenticatedParent verify(String idToken) {
		if (idToken == null || idToken.isBlank()) {
			throw new UnauthenticatedException("Missing token");
		}
		try {
			FirebaseToken token = firebaseAuth.getObject().verifyIdToken(idToken);
			return new AuthenticatedParent(token.getUid(), token.getName(), token.getEmail());
		} catch (FirebaseAuthException | IllegalArgumentException e) {
			throw new UnauthenticatedException("Invalid or expired token", e);
		}
	}
}
