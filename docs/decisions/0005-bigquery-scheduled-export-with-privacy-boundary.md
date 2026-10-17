# 0005. BigQuery export: scheduled job, field allowlist, pseudonyms

- **Status:** accepted; code and tests done; dataset created and first exports run from a developer machine on 2026-10-15 (privacy scan found 0 hits). The scheduled Cloud Run Job is **not deployed yet** (groundwork configured 2026-10-15: service account, secret, empty image repository, paused scheduler job). **Date:** 2026-10-15. Deployed 2026-10-16: Cloud Run Job `aram-analytics-export` runs daily at 02:00 Asia/Kolkata (README 3f). Normal runs only prepare tables; views are set up by the manual `--init-only` run (the job account has no access to the views dataset).
- **Architecture sections affected:** 5 (tables), 9 (phases), 10 (security, cost).

**Context.** The spec wants BigQuery as an analytical copy with minimal sensitive data. The user set the rules: dataset in `us-central1` (same as
Firestore), a scheduled Cloud Run Job (event-driven later), an explicit allowlist, no child names, exact birth dates, parent emails or Firebase uids.

**Decision.** `analytics/ExportTables.java` is the allowlist; rows are built column by column from named fields. Parent and child ids become keyed
`HMAC-SHA256` pseudonyms (key `ANALYTICS_HMAC_KEY`, never defaulted); birth month becomes an age band; family dates are coarsened. Snapshot tables are
merged to mirror Firestore (deletions propagate); the assessment history is incremental with a checkpoint that only advances on success. Analyst views
hide groups under 5 families. `fact_content_assessments` has no expiry (public catalog data; expiry would break latest-assessment queries); the run log
expires after 90 days. Daily schedule (02:00 IST) when deployed; local run and a canary check come first.

**Consequences.** A sentinel-based test suite (`AnalyticsPrivacyTest`) must keep passing; adding a column is a deliberate, reviewed act.
