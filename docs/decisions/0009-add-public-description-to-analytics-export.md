# 0009. Add the public source description to the analytics export

- **Status:** accepted (the user asked to build the recommendation engine on 2026-10-15; code done, the live table migrates on the next export run). **Date:** 2026-10-15.
- **Architecture sections affected:** 5.2 `dim_content_sources` (the spec's example omits a description column), ADR 0005 allowlist.

**Context.** Embedding-based recommendations (ADR 0008) need the text that describes each source. `dim_content_sources` currently exports no description.

**Proposal.** Add `description` (public catalog text, truncated to 500 characters) to the `dim_content_sources` allowlist, with the privacy tests updated so
parent- and child-authored text still cannot appear. The text is the platform's public channel/show description or our own curated one, not personal data.
Changing a column means a table migration (the schema-drift check blocks silent changes).

**Consequences if accepted.** `ExportTables`, the extractor, the canary tests and a migration of the live table change together.

**Implementation.** `description` is the last column of `dim_content_sources` (public text, trimmed, cut to 500 characters, null when blank).
Because `row_hash` is appended after the definition's columns, a column added later sits after `row_hash` in the live table, so the schema check
now compares columns **by name and type, not position**, and `initialize` runs an additive migration (`ALTER`-style schema update that only
appends missing columns; it never drops or retypes). Every row's hash changes once, so the next export rewrites each source with its description.
YouTube-derived text follows ADR 0011: a stale source's description is blanked in Firestore and the next snapshot blanks it here too.
