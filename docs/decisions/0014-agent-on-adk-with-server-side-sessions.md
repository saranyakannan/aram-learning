# 0014. The Aram Agent runs on Google's Agent Development Kit (ADK) with server-side sessions, and no long-term memory

- **Status:** accepted, implemented 2026-10-17. **Date:** 2026-10-17.
- **Architecture sections affected:** 2 (system), 3.16 (new collection `adk-session`), 10 (security). Sections 1 and 8 are unchanged: the backend stays authoritative for safety, authorization and approval.
- **Supersedes:** nothing. It changes *how* the model loop runs and where the chat text is kept; the four tools, the strict check, the screening (0013) and Save are as before.

**Context.** The agent was a hand-written loop (about 25 lines in `AgentService.respond`) over our own `GeminiClient`, and the browser re-sent the last 10 messages every turn so the server
stayed stateless. That works, but (a) the loop, tool declarations and Gemini translation are code every agent framework already provides, (b) a refreshed page lost the conversation, and
(c) the browser-supplied history is untrusted input. ADK for Java is Google's framework for exactly this shape (an LLM agent, function tools, sessions) and runs against Gemini on Vertex AI,
which we already use. No scoring criterion requires it; we chose it because it is the better-fitting design.

**Decision.**
1. **Framework.** `AdkAgentEngine` builds an ADK `LlmAgent` per request (`com.google.adk:google-adk` 1.12.0) on Gemini via Vertex AI, with the same four function tools
   (`find_channel`, `assess_channel`, `find_podcast`, `assess_podcast`). ADK owns the model loop (call, run tools, feed results back, step limit). **The tool bodies are the application's
   existing ones**: they receive the parent id and the child's age from the server (closed over per request), never from the model, and still call the safety service, the staging service and the
   catalog. A cap on tool calls per turn and the ADK step limit stop a runaway loop.
2. **Switch and fallback.** `aram.agent.engine=custom|adk` (default `custom`; the deployed service sets `adk`). Any ADK failure falls back, for that request, to the built-in loop. Inside ADK,
   `FailoverLlm` retries the fallback model (`gemini-2.5-flash`) when the primary stalls or fails and stays on it for the rest of that run, the same policy as the built-in loop.
3. **Sessions.** With `aram.agent.sessions-enabled=true` the conversation lives on the server as an **ADK session in Firestore** (`FirestoreSessionService`, collection `adk-session`, see architecture 3.16).
   The browser sends only a `sessionId` and the new message; a missing id starts a new conversation and the reply returns the id. `GET /api/me/agent/session` returns the latest conversation (text
   and the last candidate cards) so a reload resumes it; "New chat" deletes it. The browser's own `history` is **ignored** when sessions are on (it was untrusted input).
4. **Ownership.** A session is stored under the signed-in parent's id (taken from the verified token). Another parent's session id, an expired one, an unknown one and a malformed one all return
   **404** with the same message, so nothing leaks. Nothing is read or deleted without that check.
5. **What is stored.** Only the plain chat text (what the parent typed, what Aram answered) and the last answer's candidate cards (public channel/show facts, so a reload can show the cards again).
   **Tool calls and their results are not stored**: they contain third-party text, they are cheap to redo, and a half-finished tool exchange cannot break a later turn. Each turn runs in a fresh in-memory ADK
   session seeded with the stored text; after the answer the user and assistant messages are appended to the persistent session. If storage fails the agent still answers (from the browser's history) and
   stores nothing.
6. **Concurrency.** One request at a time per conversation (a per-conversation lock on the instance; a second concurrent message gets 409, the UI already disables Send while waiting).
7. **Retention: 7 days since last activity** (`aram.agent.session-retention-days`). ADK's Firestore service has no TTL, so expired conversations are deleted when the same parent next opens the agent
   (`GET /session` lists their sessions and removes old ones) and when an expired id is used; "New chat" deletes at once. A scheduled sweep or a Firestore TTL policy is a possible follow-up; it does not change what is stored.
8. **No long-term memory.** There is no memory service and nothing carries over between conversations. Preferences that matter already live in the profile (child, interests, approved channels).
9. **Privacy.** Conversation text is parent-typed text. It is **never exported to BigQuery**: no export table reads `adk-session` (`AnalyticsPrivacyTest.noExportTableEverReadsTheAgentConversations`), and the model never sees other parents' sessions.
10. **Workflow state.** No new engine: the staged-submission states (`content_submissions`) already track search to approval, and the Save step stays the only approval.

**Consequences.** Cost and latency are about the same as before (same models; a few extra Firestore reads and writes per turn, roughly 0.3-0.7 s from a laptop, expected to be less in region). The ADK Java library is
labelled preview; hence the switch, the per-request fallback and the unchanged built-in loop (also the rollback: set `ARAM_AGENT_ENGINE=custom`, and `ARAM_AGENT_SESSIONS_ENABLED=false` to go back to browser history).
ADK's Firestore session service writes its own document layout (we do not own it), and returns the `user` author as the user's id, so the speaker is read from the message role. Cloud Run instances do not share the in-memory lock,
so two instances could interleave two messages of one conversation in the rare case of a double-submit across instances; the UI prevents it and the worst effect is a mis-ordered pair of messages. Not done: agent evaluation
(ADK's Java evaluation is not released), tracing through Cloud Trace (optional follow-up), a scheduled retention sweep.

**Tests.** `AdkAgentEngineTest` (parity with the built-in loop on a scripted fake model: link then assess then stage; plain name shows cards and assesses nothing; screened-out results never reach the model or cards; the model
cannot choose the parent or the age; earlier messages replayed as plain user/assistant text only; runaway loop stopped; ADK failure falls back; primary-to-fallback model failover), `AgentSessionTest` (new id, resume with cards,
ownership, malformed ids, retention, New chat, busy conversation, storage failure, sessions off, speaker detection), the privacy test above, and opt-in `AdkAgentLiveTest` (Stories Podcast on both engines with real Gemini and Apple).
