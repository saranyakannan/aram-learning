# 0008. BigQuery ML recommendations, written back to Firestore as precomputed signals

- **Status:** accepted (write-back approved by the user 2026-10-15); built and run once on the real project 2026-10-15 (78 sources embedded, signals written), blend flag off. **Date:** 2026-10-15.
- **Architecture sections affected:** 2, 8 (merging and ranking), 9 (BigQuery ML roadmap), 10 (BigQuery off the request path). Adds one Firestore
  collection that the spec does not list.

**Context.** Recommendations are rule-based today (tag overlap, then the shared eligibility filter, then a deterministic rank). The user wants a BigQuery
ML recommendation engine, starting with ages 3-4, and agreed that its results may be written back to Firestore so the live app can use them. There is
one real family, so behaviour-based learning is not possible yet.

**Decision.**
- The engine is **content-based first**: embeddings of public catalog text plus tags and age range, nearest-neighbour "more like this", and topic clusters
  (KMEANS). Collaborative models (BQML matrix factorisation) wait for real families (about 100) and anything shown must cover at least 5 families.
- **ML never decides safety, age eligibility or approval.** It only adds candidates and reorders sources that already pass `EligibilityPolicy`.
- **Privacy:** inputs are catalog text and tags only; later, aggregated parents' interest tags. Never children's watch history, names, uids or birth data.
- **Serving:** a nightly step after the export writes `source_signals/{sourceId}` to Firestore (neighbours with scores, cluster id and label, model version,
  computed time; no personal data). `RecommendationService` blends tag overlap, neighbour similarity and curation (weights in config) after the eligibility
  filter, shows a reason, and sits behind a feature flag until an offline evaluation against tag-only ranking passes. BigQuery is never called per request.

**Consequences.** New collection `source_signals` (document it in `docs/architecture.md` when built). Needs a labelled evaluation set for ages 3-4 from the
user. Synthetic data, if ever used, stays in a scratch dataset and never enters `children_content_analytics`.

**Amendment (build, 2026-10-15).**
- **Clustering runs in the backend, not in BigQuery ML.** The BigQuery docs for `CREATE MODEL ... KMEANS` do not confirm an `ARRAY<FLOAT64>` feature column,
  and an embedding is 768 numbers. `KMeans` (deterministic spherical k-means, k-means++ seed 42) clusters the embeddings read back from BigQuery;
  at hundreds of sources it is instant and free. Embeddings and `VECTOR_SEARCH` neighbours stay in BigQuery. Revisit BQML `KMEANS` if it is verified.
- **Cluster labels are deterministic**: the one or two most common category names among the members (second shown, joined with " + ", if at least 60% as common). No LLM call.
- **Engine reads only `dim_content_sources` and `dim_tags`** (public catalog); a test fails if the SQL mentions any family table.
- **Blend** (`aram.ml.blend-enabled`, default off): extra candidates are neighbours (similarity at or above `min-similarity`, default 0.70, see evaluation) of sources the child
  already has; score = `tagWeight * matchedTags + similarityWeight * similarity + curationBonus`; ages in range still sort before the one-year stretch; the
  eligibility filter runs first on every candidate; the reason "Similar to X" is returned and shown.
- **Write-back is guarded**: an empty embedding result never wipes stored signals; signals of sources that no longer exist are removed; re-running is idempotent.

**Provisional evaluation (2026-10-15, labels by Claude, not a parent).** 100 blinded pairs for ages 3-4 (20 approved sources x 5 pairs), labelled good / okay / not_related.
Good rate: embedding top-3 neighbours 85% (40 pairs; none unrelated), embedding ranks 6-8 45%, tag-only matches 35%, no-shared-tag controls 0%.
Every pair at similarity 0.70 or above was acceptable (86% good, 35 pairs); the only unrelated embedding pairs scored 0.65-0.66. So `min-similarity` is 0.70
(was 0.75, which kept only 20 pairs for no quality gain). To be confirmed by a parent's labels before `blend-enabled` is switched on; the scored files are
`backend/build/review/labelling-sample-*-labelled-by-claude.csv` and the key.

**Scheduling (2026-10-16).** Cloud Run Job `aram-recommendations` runs daily at 03:00 Asia/Kolkata, an hour after the export, as its own account `aram-recommendations`
(Firestore user, BigQuery job and connection user, writer on the `_ml` dataset, reader on the analytics dataset). The blend stays a config flag (`aram.ml.blend-enabled`)
and is on only in the developer's local config; the deployed backend service does not exist yet.

**Amendment (cold start, 2026-10-16).** A new child has no approved sources, so seeding only from approvals added nothing right after onboarding. Until the child has
`seed-count` (3) approved sources, the best tag matches (the same ranking as the tag-only list) act as **soft seeds**: their neighbours become candidates with reason
"Similar to X". A neighbour reached through a soft seed counts at `seed-damping` (0.5) of its similarity, so it never outranks the tag match it came from. Eligibility is
still applied to every candidate first. The page re-fetches suggestions after each approve or remove, and shows up to 12.
