package com.aram.backend.ml;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.aram.backend.catalog.CatalogRepository;
import com.aram.backend.catalog.ContentSource;
import com.aram.backend.catalog.SourceSignal;
import com.aram.backend.catalog.SourceSignalRepository;
import com.aram.backend.config.AramProperties;
import com.aram.backend.service.TagCatalogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The batch side of the recommendation engine (decision records 0008/0009). {@link #setup()} creates the ML dataset and the
 * embedding model; {@link #refresh()} rebuilds inputs, embeddings and neighbours in BigQuery; {@link #writeBack()} clusters
 * the embeddings, labels the clusters and writes {@code source_signals} to Firestore. None of it reads family data, and the
 * signals never make a source eligible: the recommender still applies the eligibility policy to every candidate.
 */
public class MlPipelineService {

	private static final Logger log = LoggerFactory.getLogger(MlPipelineService.class);
	private static final long CLUSTER_SEED = 42L;

	public record WriteBackResult(int signalsWritten, int signalsRemoved, int clusters) {
	}

	private final MlWarehouse warehouse;
	private final SourceSignalRepository signals;
	private final CatalogRepository catalog;
	private final TagCatalogService taxonomy;
	private final AramProperties.Ml settings;
	private final MlSql.Names names;
	private final Clock clock;
	private final ObjectMapper json = new ObjectMapper();

	public MlPipelineService(MlWarehouse warehouse, SourceSignalRepository signals, CatalogRepository catalog,
			TagCatalogService taxonomy, AramProperties.Ml settings, MlSql.Names names, Clock clock) {
		this.warehouse = warehouse;
		this.signals = signals;
		this.catalog = catalog;
		this.taxonomy = taxonomy;
		this.settings = settings;
		this.names = names;
		this.clock = clock;
	}

	/** Creates the ML dataset and the remote embedding model (needs the BigQuery connection to exist first). */
	public void setup() {
		warehouse.execute(MlSql.createDataset(names));
		warehouse.execute(MlSql.createEmbeddingModel(names));
	}

	/** The statements {@link #refresh()} would run, for a dry run. */
	public List<String> plan() {
		return List.of(MlSql.inputs(names), MlSql.embeddings(names), MlSql.neighbors(names, settings.getNeighborCount()));
	}

	/** Rebuilds inputs, embeddings and neighbours. Returns how many sources went in. */
	public int refresh() {
		int inputs = Integer.parseInt(warehouse.query(MlSql.countInputs(names)).get(0).get("n"));
		if (inputs == 0) {
			throw new IllegalStateException("No exported sources to embed; run the analytics export first");
		}
		plan().forEach(warehouse::execute);
		return inputs;
	}

	public WriteBackResult writeBack() {
		Map<String, double[]> embeddings = readEmbeddings();
		if (embeddings.isEmpty()) {
			throw new IllegalStateException("No embeddings found; refusing to replace the stored signals with nothing");
		}
		Map<String, ContentSource> sources = new HashMap<>();
		catalog.findAllSources().forEach(source -> sources.put(source.id(), source));
		embeddings.keySet().removeIf(id -> !sources.containsKey(id));

		List<String> ids = new ArrayList<>(embeddings.keySet());
		double[][] vectors = ids.stream().map(embeddings::get).toArray(double[][]::new);
		int k = Math.max(1, Math.min(settings.getClusterCount(), Math.max(1, ids.size() / 2)));
		int[] assignment = KMeans.cluster(vectors, k, CLUSTER_SEED);

		Map<Integer, List<String>> members = new TreeMap<>();
		for (int i = 0; i < ids.size(); i++) {
			members.computeIfAbsent(assignment[i], key -> new ArrayList<>()).add(ids.get(i));
		}
		Map<Integer, String> labels = new HashMap<>();
		members.forEach((cluster, memberIds) -> labels.put(cluster, label(memberIds, sources)));

		Map<String, List<SourceSignal.Neighbor>> neighbors = readNeighbors(sources.keySet());
		String modelVersion = names.endpoint();
		int written = 0;
		for (int i = 0; i < ids.size(); i++) {
			String id = ids.get(i);
			signals.save(new SourceSignal(id, neighbors.getOrDefault(id, List.of()), assignment[i], labels.get(assignment[i]),
					modelVersion, clock.instant()));
			written++;
		}
		int removed = 0;
		Set<String> current = Set.copyOf(ids);
		for (SourceSignal old : signals.findAll()) {
			if (!current.contains(old.sourceId())) {
				signals.delete(old.sourceId());
				removed++;
			}
		}
		log.info("Wrote {} source signals ({} clusters), removed {} stale", written, members.size(), removed);
		return new WriteBackResult(written, removed, members.size());
	}

	/** Deterministic label: the one or two most common category names among the members. */
	String label(List<String> memberIds, Map<String, ContentSource> sources) {
		Map<String, Integer> counts = new HashMap<>();
		for (String id : memberIds) {
			ContentSource source = sources.get(id);
			if (source == null) {
				continue;
			}
			for (String tagId : source.tagIds()) {
				counts.merge(taxonomy.categoryNameForTag(tagId), 1, Integer::sum);
			}
		}
		List<Map.Entry<String, Integer>> ranked = counts.entrySet().stream()
				.sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey())).toList();
		if (ranked.isEmpty()) {
			return "Mixed";
		}
		String first = ranked.get(0).getKey();
		if (ranked.size() > 1 && ranked.get(1).getValue() * 10 >= ranked.get(0).getValue() * 6) {
			return first + " + " + ranked.get(1).getKey();
		}
		return first;
	}

	private Map<String, double[]> readEmbeddings() {
		Map<String, double[]> result = new LinkedHashMap<>();
		for (Map<String, String> row : warehouse.query(MlSql.readEmbeddings(names))) {
			try {
				double[] vector = json.readValue(row.get("embedding_json"), double[].class);
				if (vector.length > 0) {
					result.put(row.get("source_id"), vector);
				}
			} catch (IOException e) {
				log.warn("Skipping an unreadable embedding for {}", row.get("source_id"));
			}
		}
		return result;
	}

	private Map<String, List<SourceSignal.Neighbor>> readNeighbors(Set<String> knownSources) {
		Map<String, List<SourceSignal.Neighbor>> result = new LinkedHashMap<>();
		for (Map<String, String> row : warehouse.query(MlSql.readNeighbors(names))) {
			String neighbor = row.get("neighbor_id");
			if (!knownSources.contains(neighbor)) {
				continue;
			}
			double similarity = Math.round(Double.parseDouble(row.get("similarity")) * 10000d) / 10000d;
			result.computeIfAbsent(row.get("source_id"), key -> new ArrayList<>()).add(new SourceSignal.Neighbor(neighbor, similarity));
		}
		result.values().forEach(list -> list.sort(Comparator.comparingDouble(SourceSignal.Neighbor::similarity).reversed()
				.thenComparing(SourceSignal.Neighbor::sourceId)));
		return result;
	}
}
