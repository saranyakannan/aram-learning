# 0007. Cache collections that are not in the spec

- **Status:** accepted, implemented. **Date:** 2026-10-14.
- **Architecture sections affected:** 3 (collection list), 10 (cost and scale).

**Decision.** `video_cache` (latest YouTube uploads per channel) and `episode_cache` (latest podcast episodes per show) are operational caches keyed by
source id, with a time-to-live, to avoid burning YouTube quota and re-reading feeds. They hold public platform metadata only, are never exported to BigQuery,
and are safe to delete at any time. Timestamps in all collections come from the backend clock rather than Firestore server timestamps.

**Amendment (2026-10-16).** Each `video_cache` document now also holds, per video, the public `viewCount` and `durationSeconds`, plus `topVideos`: the five most viewed
non-Short uploads among the channel's latest 30 (`youtube.pool-size`; one playlist call and one statistics call per refresh, so 2 quota units), descriptions dropped.
They feed the Kid Space "top picks" strip (`/api/me/top-picks`). Still YouTube API data kept only for the cache lifetime (3 hours), well inside the 30-day limit of
decision 0011, and never exported to BigQuery. Entries cached before this change have no `topVideos` and are refreshed on first use.

**Amendment (2026-10-17).** The watch page opens any video of an approved channel through `GET /api/channels/{id}/videos/{videoId}`. A video that is not already in the cached
latest/top lists is looked up on YouTube (one quota unit) and accepted only if its channel is the approved one; the answer is kept in memory for ten minutes and **not stored**,
so nothing new is retained beyond the cache rules above.
