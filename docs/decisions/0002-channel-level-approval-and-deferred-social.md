# 0002. Approve whole sources; defer social features and item-level approval

- **Status:** accepted, implemented. **Date:** 2026-10-14.
- **Architecture sections affected:** 3.7, 3.10-3.13, 4 (social discovery), 10 (playback authorization).

**Context.** The spec prefers item-level approval for young children and describes follows, shares and social discovery. The product is a
hackathon build with one family; the user chose to ship channel-level approval and defer the social layer entirely.

**Decision.** A parent approves a source (YouTube channel or podcast show) for a child via `child_catalog_entries`; that entry is the only thing
that authorizes playback (checked server-side). Future uploads from an approved source are therefore shown. `content_items`, `parent_follows`,
`parent_content_relationships`, `parent_content_shares` and item-level approval are **not built**. Removing a source marks the entry `REVOKED`.

**Consequences.** Known limitation: new uploads are not individually reviewed. Revisit before real launch (cheap title check, then item-level).
