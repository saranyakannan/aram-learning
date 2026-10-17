package com.aram.backend.service;

import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.aram.backend.config.AramProperties;
import com.aram.backend.model.CachedChannelVideos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

/** Reads and writes the per-channel "latest videos" cache stored in Firestore. */
@Repository
public class VideoCacheRepository {

	private static final Logger log = LoggerFactory.getLogger(VideoCacheRepository.class);

	private final Firestore firestore;
	private final AramProperties properties;

	public VideoCacheRepository(Firestore firestore, AramProperties properties) {
		this.firestore = firestore;
		this.properties = properties;
	}

	public Optional<CachedChannelVideos> findFresh(String channelId) {
		try {
			DocumentSnapshot snapshot = firestore.collection(collection()).document(channelId).get().get();
			if (!snapshot.exists()) {
				return Optional.empty();
			}
			CachedChannelVideos cached = snapshot.toObject(CachedChannelVideos.class);
			if (cached == null || isStale(cached)) {
				return Optional.empty();
			}
			return Optional.of(cached);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			log.warn("Interrupted while reading video cache for channel {}", channelId, e);
			return Optional.empty();
		} catch (ExecutionException e) {
			log.warn("Failed to read video cache for channel {}", channelId, e);
			return Optional.empty();
		}
	}

	public void save(CachedChannelVideos cachedChannelVideos) {
		try {
			firestore.collection(collection()).document(cachedChannelVideos.getChannelId())
					.set(cachedChannelVideos)
					.get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			log.warn("Interrupted while writing video cache for channel {}", cachedChannelVideos.getChannelId(), e);
		} catch (ExecutionException e) {
			log.warn("Failed to write video cache for channel {}", cachedChannelVideos.getChannelId(), e);
		}
	}

	private boolean isStale(CachedChannelVideos cached) {
		Instant fetchedAt = Instant.ofEpochMilli(cached.getFetchedAtEpochMilli());
		Duration ttl = Duration.ofMinutes(properties.getYoutube().getCacheTtlMinutes());
		return fetchedAt.plus(ttl).isBefore(Instant.now());
	}

	private String collection() {
		return properties.getFirestore().getVideoCacheCollection();
	}
}
