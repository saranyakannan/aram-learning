# 0011. Keep stored YouTube data inside YouTube's 30-day limit

- **Status:** accepted, implemented 2026-10-15 (deployed 2026-10-16 as Cloud Run Job `aram-catalog-refresh`, Sundays 04:00 Asia/Kolkata, so data is refreshed at least weekly; README 3f). **Date:** 2026-10-15.
- **Architecture sections affected:** 3.6 `content_sources` (`lastVerifiedAt`), 10 (operational considerations: platform API terms).

**Context.** YouTube's developer policies allow API data obtained without the channel owner's authorisation (titles, descriptions, subscriber counts) to be
stored only as long as needed and no longer than 30 calendar days; after that it must be refreshed or deleted (see the
[developer policies](https://developers.google.com/youtube/terms/developer-policies); it was revised in June 2026, so re-read the live text before launch).
Source documents were created on 2026-10-14/15, so the first refresh is due by about 2026-11-13, and a growing catalog makes this unavoidable.

**Decision.**
- A refresh job (`ARAM_REFRESH_ENABLED=true`, `YoutubeRefreshService`) re-reads every YouTube channel in the catalog, 50 per call (1 quota unit per call),
  refreshing handle, thumbnail, subscriber count, canonical URL and `lastVerifiedAt`. Curated names and descriptions are our own text and are kept;
  parent-submitted and builder-found sources take the current YouTube title and description.
- A channel YouTube no longer returns leaves the live catalog (`UNDER_REVIEW`, so it is never playable or offered) and loses its platform data.
  A failed lookup changes nothing and is never treated as "gone".
- Run at least every 14 days (a scheduled Cloud Run Job, same pattern as the export). Sources not verified within 30 days are treated as stale and
  their thumbnails are not served.
- Podcasts come from Apple's directory and RSS feeds, which have their own terms; they are not covered here.

**Consequences.** The data in BigQuery follows through the daily snapshot export. Another scheduled job to deploy and monitor. The catalog builder depends on this
job being in place before the catalog grows.
