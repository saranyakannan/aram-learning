# 0004. How assessment statuses gate approval and recommendation

- **Status:** accepted, implemented. **Date:** 2026-10-14 (podcasts added 2026-10-15).
- **Architecture sections affected:** 3.6, 3.8, 3.9, 4, 10. The age check is amended by [0010](0010-one-year-age-stretch-for-four-year-olds.md).

**Context.** The spec separates assessment, recommendation and approval but leaves the exact policy open.

**Decision.** One shared policy (`EligibilityPolicy`) for recommendations, the agent and Save: the source is `ACTIVE`, its latest assessment is
`ASSESSED` or `APPROVED_FOR_RECOMMENDATION`, and the child's age in months is inside the assessed range; missing age data means not eligible. A model
verdict only reaches `ASSESSED` if it is `SAFE` with `HIGH` confidence and consistent about age; otherwise the source is stored `UNDER_REVIEW` (so
duplicates are recognised) and never offered. `ASSESSED` sources can be added by any parent who asks for them but are only **recommended** to others once
`APPROVED_FOR_RECOMMENDATION` (human review, not built). The starter channels and podcasts are seeded as `APPROVED_FOR_RECOMMENDATION` by a developer
review (`CURATED_SEED`) with the limitation recorded. Podcasts marked explicit are refused in code before any model is asked.

**Consequences.** Nothing is approved on a model verdict alone: the parent's Save is required, and Save re-checks the stored assessment. A reviewer path for
promoting parent-added sources is still to be built.
