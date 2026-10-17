package com.aram.backend.podcast;

import java.util.List;

/** The Apple Podcasts genres that count as children's content; a show in any other genre is not offered from a name search. */
public final class KidGenres {

	public static final List<String> APPLE = List.of("Kids & Family", "Education for Kids", "Stories for Kids");

	private KidGenres() {
	}
}
