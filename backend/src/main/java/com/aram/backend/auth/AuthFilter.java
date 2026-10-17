package com.aram.backend.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Every {@code /api} call except the public taxonomy needs a valid {@code Authorization: Bearer <ID token>}.
 * The verified parent is placed on the request; controllers never accept a parent id from the client.
 */
public class AuthFilter extends OncePerRequestFilter {

	public static final String ATTRIBUTE = "aram.authenticatedParent";
	static final Set<String> PUBLIC_PATHS = Set.of("/api/tags");

	private static final Logger log = LoggerFactory.getLogger(AuthFilter.class);

	private final TokenVerifier verifier;
	private final ObjectMapper objectMapper;

	public AuthFilter(TokenVerifier verifier, ObjectMapper objectMapper) {
		this.verifier = verifier;
		this.objectMapper = objectMapper;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String path = request.getRequestURI();
		return !path.startsWith("/api/")
				|| HttpMethod.OPTIONS.matches(request.getMethod())
				|| PUBLIC_PATHS.contains(path);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String header = request.getHeader("Authorization");
		if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
			log.warn("Rejected {} {}: no bearer token", request.getMethod(), request.getRequestURI());
			reject(response, "Please sign in to continue.");
			return;
		}
		try {
			request.setAttribute(ATTRIBUTE, verifier.verify(header.substring(7).trim()));
		} catch (UnauthenticatedException e) {
			// The reason (expired, wrong project, bad signature) is logged; the token itself never is.
			log.warn("Rejected {} {}: {}{}", request.getMethod(), request.getRequestURI(), e.getMessage(),
					e.getCause() == null ? "" : " (" + e.getCause().getMessage() + ")");
			reject(response, "Your session has expired. Please sign in again.");
			return;
		}
		chain.doFilter(request, response);
	}

	private void reject(HttpServletResponse response, String message) throws IOException {
		response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setHeader("WWW-Authenticate", "Bearer");
		objectMapper.writeValue(response.getWriter(), Map.of(
				"timestamp", Instant.now().toString(),
				"status", 401,
				"error", "Unauthorized",
				"message", message));
	}
}
