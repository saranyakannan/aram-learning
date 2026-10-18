package com.aram.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

@ConfigurationProperties(prefix = "aram")
public class AramProperties {

	private String channelsFile = "classpath:channels.json";

	private String tagsFile = "classpath:tags.json";

	private String categoriesFile = "classpath:categories.json";

	private String podcastsFile = "classpath:podcasts.json";

	private String booksFile = "classpath:books.json";

	/** When true the application seeds the taxonomy and the curated channels into Firestore at startup. */
	private boolean seedEnabled = false;

	/** When true the application refreshes the stored YouTube data of every channel, then exits (see decision record 0011). */
	private boolean refreshEnabled = false;

	@NestedConfigurationProperty
	private final Cors cors = new Cors();

	@NestedConfigurationProperty
	private final Youtube youtube = new Youtube();

	@NestedConfigurationProperty
	private final Firestore firestore = new Firestore();

	@NestedConfigurationProperty
	private final Gemini gemini = new Gemini();

	@NestedConfigurationProperty
	private final Agent agent = new Agent();

	@NestedConfigurationProperty
	private final Podcast podcast = new Podcast();

	@NestedConfigurationProperty
	private final Export export = new Export();

	@NestedConfigurationProperty
	private final Ml ml = new Ml();

	@NestedConfigurationProperty
	private final Eligibility eligibility = new Eligibility();

	@NestedConfigurationProperty
	private final Builder builder = new Builder();

	public String getChannelsFile() {
		return channelsFile;
	}

	public void setChannelsFile(String channelsFile) {
		this.channelsFile = channelsFile;
	}

	public String getTagsFile() {
		return tagsFile;
	}

	public void setTagsFile(String tagsFile) {
		this.tagsFile = tagsFile;
	}

	public String getPodcastsFile() {
		return podcastsFile;
	}

	public void setPodcastsFile(String podcastsFile) {
		this.podcastsFile = podcastsFile;
	}

	public String getBooksFile() {
		return booksFile;
	}

	public void setBooksFile(String booksFile) {
		this.booksFile = booksFile;
	}

	public String getCategoriesFile() {
		return categoriesFile;
	}

	public void setCategoriesFile(String categoriesFile) {
		this.categoriesFile = categoriesFile;
	}

	public boolean isRefreshEnabled() {
		return refreshEnabled;
	}

	public void setRefreshEnabled(boolean refreshEnabled) {
		this.refreshEnabled = refreshEnabled;
	}

	public boolean isSeedEnabled() {
		return seedEnabled;
	}

	public void setSeedEnabled(boolean seedEnabled) {
		this.seedEnabled = seedEnabled;
	}

	public Cors getCors() {
		return cors;
	}

	public Youtube getYoutube() {
		return youtube;
	}

	public Firestore getFirestore() {
		return firestore;
	}

	public Builder getBuilder() {
		return builder;
	}

	public Eligibility getEligibility() {
		return eligibility;
	}

	public Ml getMl() {
		return ml;
	}

	public Export getExport() {
		return export;
	}

	public Podcast getPodcast() {
		return podcast;
	}

	public Gemini getGemini() {
		return gemini;
	}

	public Agent getAgent() {
		return agent;
	}

	public static class Cors {
		private String allowedOrigins = "http://localhost:5173";

		public String getAllowedOrigins() {
			return allowedOrigins;
		}

		public void setAllowedOrigins(String allowedOrigins) {
			this.allowedOrigins = allowedOrigins;
		}
	}

	public static class Youtube {
		private String apiKey = "";
		private int maxResults = 5;
		/** How many recent uploads are read to find a channel's most viewed ones (one playlist call plus one statistics call). */
		private int poolSize = 30;
		private long cacheTtlMinutes = 180;

		public String getApiKey() {
			return apiKey;
		}

		public void setApiKey(String apiKey) {
			this.apiKey = apiKey;
		}

		public int getPoolSize() {
			return poolSize;
		}

		public void setPoolSize(int poolSize) {
			this.poolSize = poolSize;
		}

		public int getMaxResults() {
			return maxResults;
		}

		public void setMaxResults(int maxResults) {
			this.maxResults = maxResults;
		}

		public long getCacheTtlMinutes() {
			return cacheTtlMinutes;
		}

		public void setCacheTtlMinutes(long cacheTtlMinutes) {
			this.cacheTtlMinutes = cacheTtlMinutes;
		}
	}

	public static class Firestore {
		private String projectId = "";
		private String videoCacheCollection = "video_cache";
		private String parentCollection = "parents";

		public String getProjectId() {
			return projectId;
		}

		public void setProjectId(String projectId) {
			this.projectId = projectId;
		}

		public String getVideoCacheCollection() {
			return videoCacheCollection;
		}

		public void setVideoCacheCollection(String videoCacheCollection) {
			this.videoCacheCollection = videoCacheCollection;
		}

		public String getParentCollection() {
			return parentCollection;
		}

		public void setParentCollection(String parentCollection) {
			this.parentCollection = parentCollection;
		}
	}

	/** Gemini on Vertex AI. The Google Cloud project is {@code aram.firestore.project-id}. */
	public static class Gemini {
		private String model = "gemini-3.8-flash";
		private String fallbackModel = "gemini-2.5-flash";
		private String location = "global";
		private int maxOutputTokens = 2048;
		private String thinkingLevel = "LOW";
		private int requestTimeoutMillis = 10_000;
		private int maxAttempts = 2;

		public String getModel() {
			return model;
		}

		public void setModel(String model) {
			this.model = model;
		}

		/** Used for the second attempt when the first model stalls or fails; blank retries the same model. */
		public String getFallbackModel() {
			return fallbackModel;
		}

		public void setFallbackModel(String fallbackModel) {
			this.fallbackModel = fallbackModel;
		}

		public String getLocation() {
			return location;
		}

		public void setLocation(String location) {
			this.location = location;
		}

		public int getMaxOutputTokens() {
			return maxOutputTokens;
		}

		public void setMaxOutputTokens(int maxOutputTokens) {
			this.maxOutputTokens = maxOutputTokens;
		}

		public int getRequestTimeoutMillis() {
			return requestTimeoutMillis;
		}

		public void setRequestTimeoutMillis(int requestTimeoutMillis) {
			this.requestTimeoutMillis = requestTimeoutMillis;
		}

		public int getMaxAttempts() {
			return maxAttempts;
		}

		public void setMaxAttempts(int maxAttempts) {
			this.maxAttempts = maxAttempts;
		}

		public String getThinkingLevel() {
			return thinkingLevel;
		}

		public void setThinkingLevel(String thinkingLevel) {
			this.thinkingLevel = thinkingLevel;
		}
	}

	/** The Aram Agent: which engine runs the tool loop. */
	public static class Agent {
		/** {@code custom} is the hand-written loop; {@code adk} runs Google's Agent Development Kit (falls back to custom on any error). */
		private String engine = "custom";

		/** When true the conversation is kept on the server (ADK sessions in Firestore) and the browser sends only a session id. */
		private boolean sessionsEnabled = false;

		/** Conversations idle for longer than this are deleted the next time the parent opens the agent. */
		private int sessionRetentionDays = 7;

		public boolean isSessionsEnabled() {
			return sessionsEnabled;
		}

		public void setSessionsEnabled(boolean sessionsEnabled) {
			this.sessionsEnabled = sessionsEnabled;
		}

		public int getSessionRetentionDays() {
			return sessionRetentionDays;
		}

		public void setSessionRetentionDays(int sessionRetentionDays) {
			this.sessionRetentionDays = sessionRetentionDays;
		}

		public String getEngine() {
			return engine;
		}

		public void setEngine(String engine) {
			this.engine = engine;
		}
	}

	/** Podcast discovery (Apple's public directory) and RSS episode reading. */
	public static class Podcast {
		private String directoryBaseUrl = "https://itunes.apple.com";
		private int cacheTtlMinutes = 360;
		private int maxEpisodes = 10;
		private int feedMaxBytes = 3_000_000;
		private int timeoutMillis = 10_000;

		public String getDirectoryBaseUrl() {
			return directoryBaseUrl;
		}

		public void setDirectoryBaseUrl(String directoryBaseUrl) {
			this.directoryBaseUrl = directoryBaseUrl;
		}

		public int getCacheTtlMinutes() {
			return cacheTtlMinutes;
		}

		public void setCacheTtlMinutes(int cacheTtlMinutes) {
			this.cacheTtlMinutes = cacheTtlMinutes;
		}

		public int getMaxEpisodes() {
			return maxEpisodes;
		}

		public void setMaxEpisodes(int maxEpisodes) {
			this.maxEpisodes = maxEpisodes;
		}

		public int getFeedMaxBytes() {
			return feedMaxBytes;
		}

		public void setFeedMaxBytes(int feedMaxBytes) {
			this.feedMaxBytes = feedMaxBytes;
		}

		public int getTimeoutMillis() {
			return timeoutMillis;
		}

		public void setTimeoutMillis(int timeoutMillis) {
			this.timeoutMillis = timeoutMillis;
		}
	}

	/**
	 * The BigQuery analytics export job. Off by default: the application only exports when started with
	 * {@code ARAM_EXPORT_ENABLED=true}, which is how the Cloud Run Job runs it. The pseudonym key comes from the
	 * environment (a Secret Manager secret in production) and is never given a default.
	 */
	public static class Export {
		private boolean enabled = false;
		private String projectId = "";
		private String dataset = "children_content_analytics";
		private String viewsDataset = "children_content_analytics_views";
		private String location = "us-central1";
		private String hmacKey = "";
		private int opsRetentionDays = 90;
		private int massDeleteGuardMinRows = 10;

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public String getProjectId() {
			return projectId;
		}

		public void setProjectId(String projectId) {
			this.projectId = projectId;
		}

		public String getDataset() {
			return dataset;
		}

		public void setDataset(String dataset) {
			this.dataset = dataset;
		}

		public String getViewsDataset() {
			return viewsDataset;
		}

		public void setViewsDataset(String viewsDataset) {
			this.viewsDataset = viewsDataset;
		}

		public String getLocation() {
			return location;
		}

		public void setLocation(String location) {
			this.location = location;
		}

		public String getHmacKey() {
			return hmacKey;
		}

		public void setHmacKey(String hmacKey) {
			this.hmacKey = hmacKey;
		}

		public int getOpsRetentionDays() {
			return opsRetentionDays;
		}

		public void setOpsRetentionDays(int opsRetentionDays) {
			this.opsRetentionDays = opsRetentionDays;
		}

		public int getMassDeleteGuardMinRows() {
			return massDeleteGuardMinRows;
		}

		public void setMassDeleteGuardMinRows(int massDeleteGuardMinRows) {
			this.massDeleteGuardMinRows = massDeleteGuardMinRows;
		}
	}

	/**
	 * The one-year age stretch (decision record 0010): a child aged {@code stretchFromChildMonths} to
	 * {@code stretchToChildMonths} (default 4 years) may be offered sources whose minimum age is up to
	 * {@code ageStretchMonths} higher than the child's age. Set {@code ageStretchMonths} to 0 to switch it off.
	 */
	public static class Eligibility {
		private int ageStretchMonths = 12;
		private int stretchFromChildMonths = 48;
		private int stretchToChildMonths = 59;

		public int getAgeStretchMonths() {
			return ageStretchMonths;
		}

		public void setAgeStretchMonths(int ageStretchMonths) {
			this.ageStretchMonths = ageStretchMonths;
		}

		public int getStretchFromChildMonths() {
			return stretchFromChildMonths;
		}

		public void setStretchFromChildMonths(int stretchFromChildMonths) {
			this.stretchFromChildMonths = stretchFromChildMonths;
		}

		public int getStretchToChildMonths() {
			return stretchToChildMonths;
		}

		public void setStretchToChildMonths(int stretchToChildMonths) {
			this.stretchToChildMonths = stretchToChildMonths;
		}
	}

	/**
	 * The catalog builder (decision record 0012): finds candidate channels and podcasts, has the model assess them, and writes a review
	 * sheet. It never approves anything. {@code maxSearches} bounds YouTube quota (a search costs about 101 units of the 10,000/day).
	 */
	public static class Builder {
		private boolean enabled = false;
		private int maxSearches = 40;
		private int maxNewCandidates = 40;
		private int targetAgeMonths = 48;
		private long minSubscribers = 1000;
		private String queriesFile = "classpath:catalog-queries.json";
		private String reviewDir = "build/review";
		private String reviewer = "owner";
		/** Apple Podcasts genres that count as children's content; shows in any other genre are never assessed. */
		private java.util.List<String> podcastGenres = java.util.List.of("Kids & Family", "Education for Kids", "Stories for Kids");

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public int getMaxSearches() {
			return maxSearches;
		}

		public void setMaxSearches(int maxSearches) {
			this.maxSearches = maxSearches;
		}

		public int getMaxNewCandidates() {
			return maxNewCandidates;
		}

		public void setMaxNewCandidates(int maxNewCandidates) {
			this.maxNewCandidates = maxNewCandidates;
		}

		public int getTargetAgeMonths() {
			return targetAgeMonths;
		}

		public void setTargetAgeMonths(int targetAgeMonths) {
			this.targetAgeMonths = targetAgeMonths;
		}

		public long getMinSubscribers() {
			return minSubscribers;
		}

		public void setMinSubscribers(long minSubscribers) {
			this.minSubscribers = minSubscribers;
		}

		public String getQueriesFile() {
			return queriesFile;
		}

		public void setQueriesFile(String queriesFile) {
			this.queriesFile = queriesFile;
		}

		public String getReviewDir() {
			return reviewDir;
		}

		public void setReviewDir(String reviewDir) {
			this.reviewDir = reviewDir;
		}

		public java.util.List<String> getPodcastGenres() {
			return podcastGenres;
		}

		public void setPodcastGenres(java.util.List<String> podcastGenres) {
			this.podcastGenres = podcastGenres;
		}

		public String getReviewer() {
			return reviewer;
		}

		public void setReviewer(String reviewer) {
			this.reviewer = reviewer;
		}
	}

	/**
	 * The BigQuery ML recommendation engine (decision records 0008 and 0009). {@code enabled} starts the batch job that builds
	 * embeddings, neighbours and clusters and writes {@code source_signals}; {@code blendEnabled} (off until the offline
	 * evaluation passes) lets the recommender use those signals. Nothing here can change eligibility.
	 */
	public static class Ml {
		private boolean enabled = false;
		private boolean blendEnabled = false;
		private String dataset = "children_content_analytics_ml";
		private String embeddingModel = "embedding_model";
		private String embeddingEndpoint = "text-embedding-005";
		private String connection = "aram-vertex";
		private int neighborCount = 8;
		private int clusterCount = 8;
		private int seedCount = 3;
		private double seedDamping = 0.5;
		private double minSimilarity = 0.70;
		/**
		 * Books are compared with channel and podcast text, which scores lower than channel-to-channel (observed 0.5 to 0.7 for the
		 * good matches), so they get their own, lower minimum (decision record 0017).
		 */
		private double bookMinSimilarity = 0.60;
		private double tagWeight = 1.0;
		private double similarityWeight = 2.0;
		private double curationBonus = 0.25;

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public boolean isBlendEnabled() {
			return blendEnabled;
		}

		public void setBlendEnabled(boolean blendEnabled) {
			this.blendEnabled = blendEnabled;
		}

		public String getDataset() {
			return dataset;
		}

		public void setDataset(String dataset) {
			this.dataset = dataset;
		}

		public String getEmbeddingModel() {
			return embeddingModel;
		}

		public void setEmbeddingModel(String embeddingModel) {
			this.embeddingModel = embeddingModel;
		}

		public String getEmbeddingEndpoint() {
			return embeddingEndpoint;
		}

		public void setEmbeddingEndpoint(String embeddingEndpoint) {
			this.embeddingEndpoint = embeddingEndpoint;
		}

		public String getConnection() {
			return connection;
		}

		public void setConnection(String connection) {
			this.connection = connection;
		}

		public int getNeighborCount() {
			return neighborCount;
		}

		public void setNeighborCount(int neighborCount) {
			this.neighborCount = neighborCount;
		}

		public int getClusterCount() {
			return clusterCount;
		}

		public void setClusterCount(int clusterCount) {
			this.clusterCount = clusterCount;
		}

		public double getSeedDamping() {
			return seedDamping;
		}

		public void setSeedDamping(double seedDamping) {
			this.seedDamping = seedDamping;
		}

		public int getSeedCount() {
			return seedCount;
		}

		public void setSeedCount(int seedCount) {
			this.seedCount = seedCount;
		}

		public double getBookMinSimilarity() {
			return bookMinSimilarity;
		}

		public void setBookMinSimilarity(double bookMinSimilarity) {
			this.bookMinSimilarity = bookMinSimilarity;
		}

		public double getMinSimilarity() {
			return minSimilarity;
		}

		public void setMinSimilarity(double minSimilarity) {
			this.minSimilarity = minSimilarity;
		}

		public double getTagWeight() {
			return tagWeight;
		}

		public void setTagWeight(double tagWeight) {
			this.tagWeight = tagWeight;
		}

		public double getSimilarityWeight() {
			return similarityWeight;
		}

		public void setSimilarityWeight(double similarityWeight) {
			this.similarityWeight = similarityWeight;
		}

		public double getCurationBonus() {
			return curationBonus;
		}

		public void setCurationBonus(double curationBonus) {
			this.curationBonus = curationBonus;
		}
	}
}
