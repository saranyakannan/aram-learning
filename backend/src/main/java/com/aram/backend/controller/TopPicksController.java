package com.aram.backend.controller;

import com.aram.backend.auth.AuthFilter;
import com.aram.backend.auth.AuthenticatedParent;
import com.aram.backend.model.TopPick;
import com.aram.backend.service.TopPicksService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The mixed "top picks" strip for Kid Space; only ever drawn from what the parent approved. */
@RestController
public class TopPicksController {

	private final TopPicksService topPicksService;

	public TopPicksController(TopPicksService topPicksService) {
		this.topPicksService = topPicksService;
	}

	@GetMapping("/api/me/top-picks")
	public List<TopPick> topPicks(@RequestAttribute(AuthFilter.ATTRIBUTE) AuthenticatedParent parent,
			@RequestParam(defaultValue = "10") int size) {
		return topPicksService.topPicks(parent.uid(), size);
	}
}
