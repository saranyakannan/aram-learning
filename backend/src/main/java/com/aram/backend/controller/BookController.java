package com.aram.backend.controller;

import com.aram.backend.auth.AuthFilter;
import com.aram.backend.auth.AuthenticatedParent;
import com.aram.backend.service.BookRecommendationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Book suggestions for the signed-in parent's child. Parent Space only; Kid Space never asks for these. */
@RestController
public class BookController {

	private final BookRecommendationService books;

	public BookController(BookRecommendationService books) {
		this.books = books;
	}

	@GetMapping("/api/me/books")
	public List<BookRecommendationService.BookPick> books(@RequestAttribute(AuthFilter.ATTRIBUTE) AuthenticatedParent parent) {
		return books.forParent(parent.uid());
	}
}
