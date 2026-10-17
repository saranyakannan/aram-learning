package com.aram.backend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.aram.backend.auth.AuthFilter;
import com.aram.backend.auth.TokenVerifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;

@Configuration
@EnableConfigurationProperties(AramProperties.class)
public class AppConfig {

	@Bean
	public Clock clock() {
		return Clock.systemUTC();
	}

	/** CORS runs as a filter ahead of {@link AuthFilter}, so even 401 responses carry the CORS headers the browser needs. */
	@Bean
	public FilterRegistrationBean<CorsFilter> corsFilter(AramProperties properties) {
		CorsConfiguration cors = new CorsConfiguration();
		cors.setAllowedOrigins(Arrays.stream(properties.getCors().getAllowedOrigins().split(","))
				.map(String::trim).toList());
		cors.setAllowedMethods(List.of("GET", "PUT", "POST", "OPTIONS"));
		cors.setAllowedHeaders(List.of("Authorization", "Content-Type"));
		cors.setMaxAge(3600L);

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/api/**", cors);

		FilterRegistrationBean<CorsFilter> registration = new FilterRegistrationBean<>(new CorsFilter(source));
		registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
		return registration;
	}

	@Bean
	public FilterRegistrationBean<AuthFilter> authFilter(TokenVerifier verifier, ObjectMapper objectMapper) {
		FilterRegistrationBean<AuthFilter> registration = new FilterRegistrationBean<>(new AuthFilter(verifier, objectMapper));
		registration.addUrlPatterns("/api/*");
		registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
		return registration;
	}
}
