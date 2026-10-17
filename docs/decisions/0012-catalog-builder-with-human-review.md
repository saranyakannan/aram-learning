# 0012. A catalog builder that finds and drafts, and a person who approves

- **Status:** accepted (user asked to scale beyond hand-picked lists, 2026-10-15); code and tests done, not yet used for a real batch. **Date:** 2026-10-15.
- **Architecture sections affected:** 3.6 (`originTypes`: new value `CATALOG_BUILDER`, provenance not approval), 3.8 (a `HUMAN_REVIEW` assessor), 3.9 (the
  builder does not use parent submissions), 4 (global discovery candidates), non-negotiable rules ("parent submission does not mean approved", independent
  age and safety checks).

**Context.** The catalog has about 30 hand-picked sources; a recommendation engine (ADR 0008) needs a few hundred for ages 3-4.

**Decision.**
- A job (`ARAM_BUILDER_ENABLED=true`) runs a reviewed list of searches (`catalog-queries.json`): YouTube channel searches (about 101 quota units each, capped per
  run) and Apple Podcasts directory searches (free). Candidates are filtered before any model call: already known, duplicate, too small, podcasts marked
  explicit or outside children's genres, text mostly in another script, names that say a regional language (Hindi, Tamil, ...) or a declared non-English YouTube language, feeds not in English (English first); channels YouTube marks *not made for kids* are
  held back for an explicit human decision; look-alikes of existing sources, or of each other, are flagged.
- Survivors get the same strict model check the agent uses and are stored with provenance `CATALOG_BUILDER`: a confident SAFE becomes `ASSESSED`, anything
  else `UNDER_REVIEW` (never offered, never reassessed). **The builder cannot set `APPROVED_FOR_RECOMMENDATION`.**
- A person reviews a CSV sheet and applies it (`--apply-review`). Only then is a source promoted, by appending a `HUMAN_REVIEW` assessment (reviewer recorded,
  edited tags and ages validated against the taxonomy). The whole sheet is validated first, applying twice is harmless, a source the model refused can be
  rejected but never approved. The same path can promote parent-submitted `ASSESSED` sources, closing the reviewer-path gap noted in ADR 0004.
- Sheet cells that a spreadsheet would run as formulas are neutralised on the way out.

**Consequences.** Review time is the limit on catalog growth (a minute or two per source). Quota and Gemini cost are bounded by per-run caps. The YouTube 30-day
refresh (ADR 0011) must be running before the catalog grows. Hindi and regional-language content is a later batch that needs a language field and reviewers who
read those languages.
