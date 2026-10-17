# 0003. Keep categories and tags as reviewed JSON in the repo

- **Status:** accepted, implemented. **Date:** 2026-10-14.
- **Architecture sections affected:** 3.3 `categories`, 3.4 `tags`, 5 `dim_categories`/`dim_tags`.

**Context.** The spec lists the taxonomy as Firestore collections and warns against the model inventing near-duplicate categories. There is no
admin UI yet.

**Decision.** `categories.json` and `tags.json` are the reviewed source of truth, with ages in months. The seed job mirrors them into the Firestore
`categories` and `tags` collections (idempotent) so analytics can read them there. The agent may only choose existing tag ids.

**Consequences.** Taxonomy changes go through code review and a re-run of the seed job, not through Firestore edits. Same for the curated starter lists
(`channels.json`, `podcasts.json`).
