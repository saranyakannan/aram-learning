package com.aram.backend.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.aram.backend.catalog.InMemoryStores.source;
import static com.aram.backend.catalog.InMemoryStores.youtubeId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Counts the round trips to Firestore: sources are cached for a minute and fetched in batches. */
class CatalogRepositoryCacheTest {

	private FirestoreOps firestore;
	private CatalogRepository repository;
	private ContentSource a;
	private ContentSource b;
	private ContentSource c;

	private static FirestoreOps.Doc doc(ContentSource source) {
		return new FirestoreOps.Doc(source.id(), source.toMap());
	}

	@BeforeEach
	void setUp() {
		firestore = mock(FirestoreOps.class);
		repository = new CatalogRepository(firestore);
		a = source(youtubeId('a'), "A", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 24, 71, "math");
		b = source(youtubeId('b'), "B", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 24, 71, "music");
		c = source(youtubeId('c'), "C", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 24, 71, "math");
	}

	@Test
	void aSourceReadTwiceIsFetchedOnce() {
		when(firestore.get(Collections.CONTENT_SOURCES, a.id())).thenReturn(Optional.of(doc(a)));

		assertThat(repository.findSource(a.id())).get().extracting(ContentSource::name).isEqualTo("A");
		assertThat(repository.findSource(a.id())).isPresent();

		verify(firestore, times(1)).get(Collections.CONTENT_SOURCES, a.id());
	}

	@Test
	void manySourcesAreFetchedInOneBatchNotOneByOne() {
		when(firestore.getAll(org.mockito.ArgumentMatchers.eq(Collections.CONTENT_SOURCES), any()))
				.thenReturn(List.of(doc(a), doc(b), doc(c)));

		List<ContentSource> found = repository.findSourcesByIds(List.of(c.id(), a.id(), b.id()));

		assertThat(found).extracting(ContentSource::name).containsExactly("C", "A", "B");
		verify(firestore, times(1)).getAll(org.mockito.ArgumentMatchers.eq(Collections.CONTENT_SOURCES), any());
		verify(firestore, never()).get(anyString(), anyString());
	}

	@Test
	void onlyTheSourcesNotAlreadyCachedAreFetchedAndUnknownIdsAreLeftOut() {
		when(firestore.get(Collections.CONTENT_SOURCES, a.id())).thenReturn(Optional.of(doc(a)));
		repository.findSource(a.id());
		when(firestore.getAll(org.mockito.ArgumentMatchers.eq(Collections.CONTENT_SOURCES), any())).thenReturn(List.of(doc(b)));

		List<ContentSource> found = repository.findSourcesByIds(List.of(a.id(), b.id(), "youtube_missing"));

		assertThat(found).extracting(ContentSource::name).containsExactly("A", "B");
		org.mockito.ArgumentCaptor<java.util.Collection<String>> asked = org.mockito.ArgumentCaptor.forClass(java.util.Collection.class);
		verify(firestore).getAll(org.mockito.ArgumentMatchers.eq(Collections.CONTENT_SOURCES), asked.capture());
		assertThat(asked.getValue()).containsExactlyInAnyOrder(b.id(), "youtube_missing");
	}

	@Test
	void writingASourceMakesTheNextReadGoToFirestoreAgain() {
		when(firestore.get(Collections.CONTENT_SOURCES, a.id())).thenReturn(Optional.of(doc(a)));
		repository.findSource(a.id());

		repository.saveSource(a);
		repository.findSource(a.id());

		verify(firestore, times(2)).get(Collections.CONTENT_SOURCES, a.id());
	}

	@Test
	void theRecommendableListIsQueriedOnceUntilASourceChanges() {
		when(firestore.where(org.mockito.ArgumentMatchers.eq(Collections.CONTENT_SOURCES), anyMap())).thenReturn(List.of(doc(a), doc(b)));

		repository.findSources(Statuses.Catalog.ACTIVE, Statuses.Assessment.APPROVED_FOR_RECOMMENDATION);
		repository.findSources(Statuses.Catalog.ACTIVE, Statuses.Assessment.APPROVED_FOR_RECOMMENDATION);
		verify(firestore, times(1)).where(org.mockito.ArgumentMatchers.eq(Collections.CONTENT_SOURCES), anyMap());

		repository.saveSource(c);
		repository.findSources(Statuses.Catalog.ACTIVE, Statuses.Assessment.APPROVED_FOR_RECOMMENDATION);
		verify(firestore, times(2)).where(org.mockito.ArgumentMatchers.eq(Collections.CONTENT_SOURCES), anyMap());
	}

	@Test
	void aSourceThatDoesNotExistIsNotRememberedAsMissing() {
		when(firestore.get(Collections.CONTENT_SOURCES, a.id())).thenReturn(Optional.empty(), Optional.of(doc(a)));

		assertThat(repository.findSource(a.id())).isEmpty();
		assertThat(repository.findSource(a.id())).isPresent();
	}
}
