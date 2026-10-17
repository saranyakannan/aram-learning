# 0015. Podcast playability check, and email/password sign-in next to Google

- **Status:** accepted, implemented 2026-10-17. **Date:** 2026-10-17.
- **Architecture sections affected:** 10 (playback availability note added); no collection or field changes.

**Context.** (1) A curated show, *The Imagine Neighborhood*, was approved and listed with working-looking episodes, but its publisher's audio host answered 403 for every file (all 10 sampled of 159 episodes),
so a child pressed play and nothing started. The safety check reads text only and never touched the audio. (2) A demonstration login is needed that does not require a Google account, without a guest mode that bypasses sign-in.

**Decisions.**
1. `PodcastAudioCheck` requests the first kilobyte of the newest episode's audio through the same guarded fetcher as feeds. A clear refusal (4xx other than 408/429, or an HTML/XML/JSON body served as a 200)
   marks the show unavailable: `PodcastFeedService.episodesFor` returns nothing for it (so Kid Space shows no dead tile) and the agent refuses to stage it, including shows already in the catalog. Timeouts, rate limits,
   5xx and unreachable hosts are *unknown* and never hide a show. Verdicts are held in memory only (60 minutes for working audio, 15 for a refusal); nothing is stored and no field is added.
2. The sign-in page offers Firebase **Email/Password** (sign in and create account) beside Google. The backend is unchanged: it already verifies Firebase ID tokens whatever the provider, and the parent id still comes only from the verified token.
   The provider is switched on in the Firebase console (Authentication, Sign-in method). A demonstration login is created by the owner in the app and shared privately, never in the repository, the docs or any recording.

**Consequences.** One extra small HTTP request per show per hour at most. A show whose host blocks server-side requests but works in browsers would be hidden only if it answers a definite 4xx to the server; that was not seen on any curated
show (four answered ambiguously and stay visible). Anyone can create an account with the email form; that is the same exposure as Google sign-in (an account only holds that parent's own data).
Tests: `PodcastAudioCheckTest`, `PodcastFeedServiceTest`, `AgentServiceTest`, and the opt-in `PodcastLiveTest` that checks every curated show.
