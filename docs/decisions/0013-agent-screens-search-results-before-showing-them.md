# 0013. The agent screens name-search results for the child's age before showing them

- **Status:** accepted, implemented 2026-10-16. **Date:** 2026-10-16.
- **Architecture sections affected:** 1 (non-negotiable rules: anything not clearly safe is blocked), 2 and 8 (the agent and eligibility); no data model change.

**Context.** When a parent asked the agent for "Stories Podcast", the cards offered four results, including "True Cheating Stories 2025" (NSFW), a horror anthology and
an adult insomnia show. The strict safety check (`assess` / `assessPodcast`) only ran **after** the parent picked a card, so nothing stopped unsuitable results from
being shown; the agent's instructions only covered what to do with a pick. Links and ids were fine (they go straight to the check), the gap was name search, for both
podcasts (Apple's top 5) and YouTube channels (search with strict safe search).

**Decision.** Name searches are screened before the parent sees them, in two steps, and the existing full check after the pick stays as the authority:
1. **Hard rules in code (podcasts):** a show marked explicit, or outside the children's Apple genres (`KidGenres`: Kids & Family, Education for Kids, Stories for Kids), is never offered.
2. **One model call for the whole list** (`ChannelSafetyService.screen`): title, author, genre and description go to Gemini with the child's age; an item is kept only if the model says it is
   clearly children's content suitable for that age. It is **fail-closed**: anything the model does not mention is dropped, and if the model cannot be reached nothing is shown (the
   agent says so and suggests a link instead). Results come back as ids and booleans only, and the text is treated as untrusted data.
3. The tool result tells the agent how many were hidden (`hiddenCount`) and the agent says so in one sentence; it is told to offer only the candidates returned.

**Why not a separate agent.** The verifier already exists as a separate, stricter model call (`ChannelSafetyService`); it just ran too late. Screening reuses that service and the same
Gemini model, adds one call per name search (cents, about a second), and keeps one place that decides what "suitable" means. The checked-after-pick step is unchanged, so the screening
can only remove options; it can never make anything eligible.

**Consequences.** Some legitimate shows will not appear in a name search (a show in a non-children's Apple genre, or one the model is unsure about, or one aimed at older children than
the child); the parent can still add them by link, where the full check decides. A live check on the reported search kept the kids' "Stories Podcast" and "Wow in the World" and hid the
three adult shows. Tests: `ChannelSafetyServiceTest` (screening, fail-closed), `AgentServiceTest` (hard rules, hidden count, nothing shown when screening is unavailable), and an
opt-in live test in `ChannelSafetyLiveTest`.
