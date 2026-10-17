package com.aram.backend.ml;

import com.aram.backend.catalog.CatalogRepository;
import com.aram.backend.catalog.InMemoryStores;
import com.aram.backend.catalog.SourceSignal;
import com.aram.backend.catalog.SourceSignalRepository;
import com.aram.backend.catalog.Statuses;
import com.aram.backend.config.AramProperties;
import com.aram.backend.service.TagCatalogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.aram.backend.catalog.InMemoryStores.source;
import static com.aram.backend.catalog.InMemoryStores.youtubeId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MlPipelineServiceTest {

	static class FakeWarehouse implements MlWarehouse {
		final List<String> executed = new ArrayList<>();
		String inputCount = "4";
		List<Map<String, String>> embeddings = new ArrayList<>();
		List<Map<String, String>> neighbors = new ArrayList<>();

		@Override
		public void execute(String sql) {
			executed.add(sql);
		}

		@Override
		public List<Map<String, String>> query(String sql) {
			if (sql.contains("embedding_json")) {
				return embeddings;
			}
			if (sql.contains("neighbor_id")) {
				return neighbors;
			}
			return List.of(Map.of("n", inputCount));
		}
	}

	static class MemorySignals extends SourceSignalRepository {
		final Map<String, SourceSignal> stored = new LinkedHashMap<>();

		MemorySignals() {
			super(null);
		}

		@Override
		public java.util.Optional<SourceSignal> find(String sourceId) {
			return java.util.Optional.ofNullable(stored.get(sourceId));
		}

		@Override
		public List<SourceSignal> findAll() {
			return new ArrayList<>(stored.values());
		}

		@Override
		public void save(SourceSignal signal) {
			stored.put(signal.sourceId(), signal);
		}

		@Override
		public void delete(String sourceId) {
			stored.remove(sourceId);
		}
	}

	private static String sid(char letter) {
		return com.aram.backend.catalog.ContentSource.idForYoutubeChannel(youtubeId(letter));
	}

	private static final String A = sid('a');
	private static final String B = sid('b');
	private static final String C = sid('c');
	private static final String D = sid('d');

	private FakeWarehouse warehouse;
	private MemorySignals signals;
	private InMemoryStores.Catalog catalog;
	private MlPipelineService service;

	@BeforeEach
	void setUp() {
		warehouse = new FakeWarehouse();
		signals = new MemorySignals();
		catalog = new InMemoryStores.Catalog();
		for (var s : List.of(
				source(youtubeId('a'), "Counting A", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 24, 71, "math"),
				source(youtubeId('b'), "Counting B", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 24, 71, "math"),
				source(youtubeId('c'), "Songs C", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 24, 71, "music"),
				source(youtubeId('d'), "Songs D", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 24, 71, "music"))) {
			catalog.sources.put(s.id(), s);
		}
		TagCatalogService taxonomy = mock(TagCatalogService.class);
		when(taxonomy.categoryNameForTag(anyString())).thenAnswer(call -> "math".equals(call.getArgument(0)) ? "Math & Counting" : "Songs & Music");
		AramProperties.Ml ml = new AramProperties.Ml();
		ml.setClusterCount(2);
		var names = new MlSql.Names("proj", "us-central1", "analytics", "analytics_ml", "embedding_model", "text-embedding-005", "aram-vertex");
		service = new MlPipelineService(warehouse, signals, catalog, taxonomy, ml, names,
				Clock.fixed(Instant.parse("2026-10-15T10:00:00Z"), ZoneOffset.UTC));
	}

	private void embed(String id, String json) {
		warehouse.embeddings.add(Map.of("source_id", id, "embedding_json", json));
	}

	private void neighbour(String id, String other, String similarity, String rank) {
		warehouse.neighbors.add(Map.of("source_id", id, "neighbor_id", other, "similarity", similarity, "neighbor_rank", rank));
	}

	private void typicalResults() {
		embed(A, "[1.0,0.1,0.0]");
		embed(B, "[0.9,0.0,0.1]");
		embed(C, "[0.0,1.0,0.1]");
		embed(D, "[0.1,0.9,0.0]");
		neighbour(A, B, "0.93", "1");
		neighbour(A, D, "0.40", "2");
		neighbour(C, D, "0.91", "1");
	}

	@Test
	void setupCreatesTheDatasetThenTheRemoteModel() {
		service.setup();

		assertThat(warehouse.executed).hasSize(2);
		assertThat(warehouse.executed.get(0)).startsWith("CREATE SCHEMA IF NOT EXISTS");
		assertThat(warehouse.executed.get(1)).startsWith("CREATE MODEL IF NOT EXISTS");
	}

	@Test
	void refreshRunsInputsEmbeddingsNeighboursInOrder() {
		assertThat(service.refresh()).isEqualTo(4);

		assertThat(warehouse.executed).hasSize(3);
		assertThat(warehouse.executed.get(0)).contains("ml_source_inputs");
		assertThat(warehouse.executed.get(1)).contains("ML.GENERATE_EMBEDDING");
		assertThat(warehouse.executed.get(2)).contains("VECTOR_SEARCH");
	}

	@Test
	void refreshWithNothingExportedStopsBeforeSpendingOnEmbeddings() {
		warehouse.inputCount = "0";

		assertThatThrownBy(service::refresh).isInstanceOf(IllegalStateException.class).hasMessageContaining("export");
		assertThat(warehouse.executed).isEmpty();
	}

	@Test
	void writeBackStoresNeighboursClustersAndLabelsWithoutAnyPersonalData() {
		typicalResults();

		var result = service.writeBack();

		assertThat(result.signalsWritten()).isEqualTo(4);
		SourceSignal a = signals.stored.get(A);
		assertThat(a.neighbors()).extracting(SourceSignal.Neighbor::sourceId).containsExactly(B, D);
		assertThat(a.neighbors().get(0).similarity()).isEqualTo(0.93);
		assertThat(a.modelVersion()).isEqualTo("text-embedding-005");
		assertThat(a.computedAt()).isEqualTo(Instant.parse("2026-10-15T10:00:00Z"));
		assertThat(signals.stored.get(A).clusterId()).isEqualTo(signals.stored.get(B).clusterId());
		assertThat(signals.stored.get(C).clusterId()).isEqualTo(signals.stored.get(D).clusterId());
		assertThat(signals.stored.get(A).clusterId()).isNotEqualTo(signals.stored.get(C).clusterId());
		assertThat(a.clusterLabel()).isEqualTo("Math & Counting");
		assertThat(signals.stored.get(C).clusterLabel()).isEqualTo("Songs & Music");
		assertThat(a.toMap().keySet()).containsExactly("sourceId", "neighbors", "clusterId", "clusterLabel", "modelVersion", "computedAt");
	}

	@Test
	void signalsForSourcesThatNoLongerExistAreRemovedAndUnknownNeighboursAreDropped() {
		typicalResults();
		neighbour(A, sid('z'), "0.99", "0");
		signals.stored.put(sid('y'), new SourceSignal(sid('y'), List.of(), 0, "x", "old", Instant.EPOCH));

		var result = service.writeBack();

		assertThat(result.signalsRemoved()).isEqualTo(1);
		assertThat(signals.stored).doesNotContainKey(sid('y'));
		assertThat(signals.stored.get(A).neighbors()).extracting(SourceSignal.Neighbor::sourceId).doesNotContain(sid('z'));
	}

	@Test
	void anEmptyEmbeddingResultNeverWipesTheStoredSignals() {
		signals.stored.put(A, new SourceSignal(A, List.of(), 0, "x", "old", Instant.EPOCH));

		assertThatThrownBy(service::writeBack).isInstanceOf(IllegalStateException.class);
		assertThat(signals.stored).containsKey(A);
	}

	@Test
	void runningWriteBackTwiceGivesTheSameSignals() {
		typicalResults();
		service.writeBack();
		Map<String, SourceSignal> first = new LinkedHashMap<>(signals.stored);
		service.writeBack();

		assertThat(signals.stored).isEqualTo(first);
	}
}
