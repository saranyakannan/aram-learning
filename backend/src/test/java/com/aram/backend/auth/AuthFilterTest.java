package com.aram.backend.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.aram.backend.config.AppConfig;
import com.aram.backend.config.AramProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthFilterTest {

	@RestController
	static class EchoController {
		@GetMapping("/api/me/echo")
		String echo(@RequestAttribute(AuthFilter.ATTRIBUTE) AuthenticatedParent parent,
				@RequestParam(required = false) String parentId) {
			return parent.uid() + "|" + parentId;
		}

		@GetMapping("/api/tags")
		String tags() {
			return "public-tags";
		}

		@GetMapping("/other/page")
		String other() {
			return "not-api";
		}
	}

	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		TokenVerifier verifier = token -> {
			if (token.equals("good-token")) {
				return new AuthenticatedParent("uid-123", "Asha", "asha@example.com");
			}
			throw new UnauthenticatedException("bad");
		};
		AppConfig config = new AppConfig();
		mvc = MockMvcBuilders.standaloneSetup(new EchoController())
				.addFilters(config.corsFilter(new AramProperties()).getFilter(),
						config.authFilter(verifier, new ObjectMapper()).getFilter())
				.build();
	}

	@Test
	void aValidTokenReachesTheControllerWithTheVerifiedUid() throws Exception {
		mvc.perform(get("/api/me/echo").header("Authorization", "Bearer good-token"))
				.andExpect(status().isOk())
				.andExpect(content().string("uid-123|null"));
	}

	@Test
	void aClientSuppliedParentIdCanNeverReplaceTheVerifiedOne() throws Exception {
		mvc.perform(get("/api/me/echo?parentId=someone-elses-id").header("Authorization", "Bearer good-token"))
				.andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.startsWith("uid-123|")));
	}

	@Test
	void theBearerSchemeIsCaseInsensitive() throws Exception {
		mvc.perform(get("/api/me/echo").header("Authorization", "bearer good-token")).andExpect(status().isOk());
	}

	@Test
	void noTokenIsRejectedWithAClearJsonError() throws Exception {
		mvc.perform(get("/api/me/echo"))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string("WWW-Authenticate", "Bearer"))
				.andExpect(jsonPath("$.status").value(401))
				.andExpect(jsonPath("$.message").value("Please sign in to continue."));
	}

	@Test
	void aWrongSchemeOrABadTokenIsRejected() throws Exception {
		mvc.perform(get("/api/me/echo").header("Authorization", "Basic Zm9vOmJhcg==")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/me/echo").header("Authorization", "Bearer forged"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value("Your session has expired. Please sign in again."));
		mvc.perform(get("/api/me/echo").header("Authorization", "Bearer ")).andExpect(status().isUnauthorized());
	}

	@Test
	void theTaxonomyIsPublicAndNonApiPathsAreLeftAlone() throws Exception {
		mvc.perform(get("/api/tags")).andExpect(status().isOk()).andExpect(content().string("public-tags"));
		mvc.perform(get("/other/page")).andExpect(status().isOk());
	}

	@Test
	void aPreflightRequestPassesWithoutATokenAndCarriesCorsHeaders() throws Exception {
		mvc.perform(options("/api/me/echo")
						.header("Origin", "http://localhost:5173")
						.header("Access-Control-Request-Method", "GET")
						.header("Access-Control-Request-Headers", "authorization"))
				.andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
				.andExpect(header().string("Access-Control-Allow-Headers", org.hamcrest.Matchers.containsStringIgnoringCase("authorization")));
	}

	@Test
	void evenARejectedRequestCarriesCorsHeadersSoTheBrowserCanReadTheError() throws Exception {
		mvc.perform(get("/api/me/echo").header("Origin", "http://localhost:5173"))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
	}

	@Test
	void anUnknownOriginIsRefusedEvenWithAValidToken() throws Exception {
		mvc.perform(get("/api/me/echo").header("Origin", "http://evil.example").header("Authorization", "Bearer good-token"))
				.andExpect(status().isForbidden())
				.andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
	}
}
