package com.aram.backend.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Firestore document caching the most recently fetched videos for one channel. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CachedChannelVideos {
	private String channelId;
	private List<Video> videos;
	private long fetchedAtEpochMilli;
	/** The most viewed of the channel's recent uploads (descriptions dropped); null in entries cached before this existed. */
	private List<Video> topVideos;

	public CachedChannelVideos(String channelId, List<Video> videos, long fetchedAtEpochMilli) {
		this(channelId, videos, fetchedAtEpochMilli, null);
	}
}
