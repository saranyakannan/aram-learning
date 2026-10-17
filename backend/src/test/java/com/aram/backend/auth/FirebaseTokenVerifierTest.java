package com.aram.backend.auth;

import com.google.firebase.auth.FirebaseAuth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FirebaseTokenVerifierTest {

	@SuppressWarnings("unchecked")
	private static FirebaseTokenVerifier verifierWhoseSdkRejects(RuntimeException failure) throws Exception {
		FirebaseAuth sdk = mock(FirebaseAuth.class);
		when(sdk.verifyIdToken("some-token")).thenThrow(failure);
		ObjectProvider<FirebaseAuth> provider = mock(ObjectProvider.class);
		when(provider.getObject()).thenReturn(sdk);
		return new FirebaseTokenVerifier(provider);
	}

	@Test
	void blankOrMissingTokensAreRejectedWithoutTouchingFirebase() {
		@SuppressWarnings("unchecked")
		ObjectProvider<FirebaseAuth> provider = mock(ObjectProvider.class);
		FirebaseTokenVerifier verifier = new FirebaseTokenVerifier(provider);

		assertThatThrownBy(() -> verifier.verify(null)).isInstanceOf(UnauthenticatedException.class);
		assertThatThrownBy(() -> verifier.verify("  ")).isInstanceOf(UnauthenticatedException.class);
		org.mockito.Mockito.verifyNoInteractions(provider);
	}

	@Test
	void aTokenTheSdkCannotParseBecomesUnauthenticated() throws Exception {
		FirebaseTokenVerifier verifier = verifierWhoseSdkRejects(new IllegalArgumentException("malformed"));

		assertThatThrownBy(() -> verifier.verify("some-token")).isInstanceOf(UnauthenticatedException.class);
	}
}
