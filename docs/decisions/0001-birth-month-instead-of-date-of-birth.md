# 0001. Store the child's birth month, not the date of birth

- **Status:** accepted, implemented. **Date:** 2026-10-14.
- **Architecture sections affected:** 3.2 `children` (spec stores `dateOfBirth`).

**Context.** Age drives eligibility, but an exact birth date is more personal data than the product needs, and the spec itself says to keep
the minimum age information required.

**Decision.** `children/{childId}.birthYearMonth` (`YYYY-MM`) is the only stored age input. Age in months is always computed
(`AgeMath`); no age is persisted. Child ids are `<parent uid>-c1` (one child per parent for now).

**Consequences.** Ages are accurate to the month. BigQuery never receives the birth month, only a coarse age band (see 0005).
