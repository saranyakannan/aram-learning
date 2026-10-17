# 0006. Defer recommendation and playback event capture

- **Status:** accepted. **Date:** 2026-10-15.
- **Architecture sections affected:** 3.14 `recommendation_events`, 5.1/5.6 `fact_recommendation_events`, `fact_playback_events`.

**Context.** Nothing records impressions, saves, dismissals or playback today, and these events are the most sensitive data (children's behaviour). The spec
says to minimise collection and apply retention and privacy controls.

**Decision.** No event capture and no event tables in BigQuery for now. Do it later as its own change, after a privacy review of what is collected, retention
and who can see it. BigQuery ML stays out until there is enough data to be meaningful.

**Consequences.** Recommendation quality cannot yet be measured from behaviour; analytics cover the catalog, assessments, interests and approvals only.
