# 0010. One-year age stretch for 4-year-olds

- **Status:** accepted (user decision 2026-10-15), implemented. **Date:** 2026-10-15.
- **Architecture sections affected:** 3.8 (assessed age ranges in months), 4 step 3 (age in the eligibility filter), non-negotiable rule
  "age suitability and safety eligibility must be checked independently of relevance ranking". Amends the age check in 0004.

**Context.** Content for 3-4-year-olds is thin, and many good shows are marked 5+ (for example Wow in the World 5-10). The user decided that a 4-year-old
may be offered content aimed at 5.

**Decision.** In `EligibilityPolicy`, for a child aged 48-59 months a source is eligible when its assessed **minimum** age is at most 12 months above the
child's age. The stretch applies only to the lower bound: nothing is added to the upper bound, to children of other ages, or to any safety check (live
catalog, a cleared assessment, human review before recommending to others). It lives in the one shared policy, so recommendations, the agent and Save agree.
Ranking is separate: sources inside the child's range come first, and a source admitted only by the stretch is marked `ageFit = false` and shown as "Aimed a
little older than <child>". The parent still has to press Save. Configurable under `aram.eligibility` (`age-stretch-months`, `stretch-from-child-months`,
`stretch-to-child-months`); 0 switches it off.

**Consequences.** A model's "not age-appropriate for a 4-year-old" on a 5+ source no longer blocks it by age alone; its safety verdict still must be `SAFE`
with `HIGH` confidence. If the stretch should cover other ages, or the upper bound, that is a new decision.
