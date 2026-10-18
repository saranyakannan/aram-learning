package com.aram.backend.controller;

import com.aram.backend.auth.AuthenticatedParent;
import com.aram.backend.auth.UnauthenticatedException;
import com.aram.backend.config.AppConfig;
import com.aram.backend.config.AramProperties;
import com.aram.backend.service.BookRecommendationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BookControllerTest {

	private BookRecommendationService service;
	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		service = mock(BookRecommendationService.class);
		var verifier = (com.aram.backend.auth.TokenVerifier) token -> {
			if (token.equals("good-token")) {
				return new AuthenticatedParent("uid-123", "Asha", "asha@example.com");
			}
			throw new UnauthenticatedException("bad");
		};
		AppConfig config = new AppConfig();
		mvc = MockMvcBuilders.standaloneSetup(new BookController(service))
				.addFilters(config.corsFilter(new AramProperties()).getFilter(), config.authFilter(verifier, new ObjectMapper()).getFilter())
				.build();
	}

	@Test
	void anUnsignedRequestIsRefusedAndNothingIsComputed() throws Exception {
		mvc.perform(get("/api/me/books")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/me/books").header("Authorization", "Bearer wrong")).andExpect(status().isUnauthorized());

		verify(service, never()).forParent(org.mockito.ArgumentMatchers.any());
	}

	@Test
	void aSignedInParentGetsOnlyTheirOwnPicksAndAParentIdInTheQueryIsIgnored() throws Exception {
		when(service.forParent("uid-123")).thenReturn(List.of(new BookRecommendationService.BookPick("a", "A Book", "An Author", 0, 36,
				"Desc.", null, "https://www.amazon.in/dp/0241003008", "Amazon", List.of("nature"), "Similar to Numberblocks")));

		mvc.perform(get("/api/me/books?parentId=someone-else").header("Authorization", "Bearer good-token")
				.accept(org.springframework.http.MediaType.APPLICATION_JSON))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].title").value("A Book"))
				.andExpect(jsonPath("$[0].store").value("Amazon"))
				.andExpect(jsonPath("$[0].matchedTags[0]").value("nature"))
				.andExpect(jsonPath("$[0].reason").value("Similar to Numberblocks"));
		verify(service).forParent("uid-123");
		verify(service, never()).forParent("someone-else");
	}
}
