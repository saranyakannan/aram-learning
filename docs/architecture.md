Children's Content Discovery Platform
Firestore and BigQuery Data Model Specification
Status: Initial implementation specification
Primary operational database: Google Cloud Firestore
Analytics and recommendation data platform: BigQuery
Optional ML layer: BigQuery ML
Core principle: One canonical content catalog, multiple discovery
paths, and one shared eligibility policy.
1. Goals and design principles
The application lets a parent:

1. Create a parent account and one or more child profiles.
2. Select learning interests represented by categories and tags.
3. Discover content from a pre-populated catalog.
4. Submit new YouTube channels, shows, podcast feeds, videos, or
episodes.
5. Receive an AI-assisted assessment and proposed categories/tags for
submitted content.
6. Save or approve content for a particular child.
7. Follow other parents and discover content they explicitly share.
8. Receive recommendations from both global discovery and social
discovery.
9. Keep content assessment, social sharing, and child approval as
separate concepts.

Non-negotiable rules

* A content source is stored once, even if many parents save or share
it.
* Parent submission does not automatically mean content is approved.
* A parent saving content does not automatically mean it is shared
with followers.
* A followed parent's share is a discovery signal, not an approval
signal.
* Age suitability and safety eligibility must be checked independently
of relevance ranking.
* BigQuery is for analytics and ML; Firestore is the operational
source of truth.
* A model-generated assessment must include evidence, assessment time,
and model/policy versions.
* Store age ranges in months, not strings such as `"2-3 years"`.
* Store canonical platform IDs and URLs where available; do not
deduplicate by display name alone.
* Avoid exposing one child's private viewing history or saved catalog
to other parents.

2. System architecture

```
                       PARENT / CHILD APP
                               |
                    Authentication / API
                               |
                  +------------+------------+
                  |                         |
             Firestore                 AI Agent
        Operational source of truth   Discover / resolve
                  |                   assess / tag content
                  |                         |
                  +------------+------------+
                               |
                    Content eligibility policy
                               |
                 +-------------+-------------+
                 |                           |
          Global discovery             Social discovery
       Seed + submitted sources      Explicit shares by followed
                 |                    parents, with permissions
                 +-------------+-------------+
                               |
                     Candidate deduplication
                               |
                Eligibility filter, then ranking
                               |
                    Recommendations to parent
                               |
                      Parent saves/approves
                               |
                       Firestore updates
                               |
                 Export/stream analytics events
                               |
                            BigQuery
                               |
                    BigQuery ML (later phase)
```

Responsibilities
Firestore - Parent and child profiles. - Categories, tags, and
selected interests. - Canonical content sources and individual content
items. - Content submissions and assessment records. - Parent follow
relationships and explicit content shares. - Child-specific
saved/approved catalog entries. - Operational playback eligibility
checks.
AI agent - Resolve a parent-provided name or URL to a likely
official source. - Check for an existing canonical source before
creating one. - Retrieve permitted metadata and, when available,
transcript/audio/video evidence. - Propose tags and age suitability. -
Produce a structured assessment with evidence limitations. - Never
bypass approval or eligibility policy.
Implementation (decision records 0013, 0014): the agent runs on Google's Agent Development Kit
for Java with Gemini on Vertex AI, with four application-owned tools (find/assess channel,
find/assess podcast). The tools run inside the backend with the parent id and the child's age
taken from the verified token and the stored profile, never from the model. Name-search
results are screened for the child's age before the parent sees them. The conversation is an
ADK session in Firestore (3.16, no long-term memory). The agent can stage a source but only
the parent's Save approves it.
BigQuery - Analytics on catalog coverage, recommendations, saves,
dismissals, and approved playback. - Offline feature tables and model
evaluation. - Candidate ranking or related-tag discovery when sufficient
data exists.
BigQuery ML - Optional later phase. Do not require ML to launch the
MVP. - It may rank eligible candidates or suggest related interests. -
It must not decide safety, age eligibility, or parent approval.
3. Firestore collection design
Firestore uses collections and documents, not relational tables. The
collection names below are recommended logical names. IDs shown in
examples are illustrative.
3.1 `parents/{parentId}`
Stores parent profile and application settings. Use the authentication
provider's stable user ID as `parentId` where appropriate.

```
{
  "displayName": "Parent A",
  "preferredLanguage": "en",
  "createdAt": "SERVER_TIMESTAMP",
  "updatedAt": "SERVER_TIMESTAMP",
  "accountStatus": "ACTIVE",
  "socialDiscoveryEnabled": true
}
```

Do not store authentication passwords in Firestore. Use Firebase
Authentication or another approved identity provider.
3.2 `children/{childId}`
Stores a child profile. Keep child data private and limit access to
authorized guardians.

```
{
  "parentId": "parent_A",
  "displayName": "Child A",
  "dateOfBirth": "2024-10-01",
  "preferredLanguage": "en",
  "createdAt": "SERVER_TIMESTAMP",
  "updatedAt": "SERVER_TIMESTAMP",
  "profileStatus": "ACTIVE"
}
```

Calculate current age from `dateOfBirth` in the application or backend.
Avoid persisting a static age that becomes stale. If a date of birth is
not needed, consider storing only the minimum age information required
by the product.
3.3 `categories/{categoryId}`
Broad learning areas.

```
{
  "name": "Songs & Music",
  "description": "Songs, nursery rhymes, rhythm, and music participation",
  "icon": "music",
  "sortOrder": 1,
  "isActive": true,
  "createdAt": "SERVER_TIMESTAMP"
}
```

Suggested seed categories: - Songs & Music - Language & Early Literacy -
Math & Counting - Science & Discovery - Nature & Animals - Stories &
Imagination - Social-Emotional Learning - Art & Creativity - Movement &
Mindfulness - Everyday Life & Routines
Categories are curated taxonomy entities. Do not let the model create
unlimited near-duplicate categories without a review or normalization
step.
3.4 `tags/{tagId}`
Specific topics and learning interests. A tag belongs to a category; a
content source or item may have multiple tags.

```
{
  "name": "Nursery Rhymes",
  "normalizedName": "nursery-rhymes",
  "categoryId": "songs-music",
  "description": "Traditional and contemporary rhymes for young children",
  "minAgeMonths": 0,
  "maxAgeMonths": 48,
  "isActive": true,
  "createdAt": "SERVER_TIMESTAMP"
}
```

Example tags: - Songs - Nursery Rhymes - Animal Sounds - Animals -
Counting - Number Recognition - Phonics - Vocabulary - Friendship -
Emotions - Nature - Movement - Bedtime Stories
Use stable tag IDs. The LLM should suggest existing tags first. If it
proposes a new tag, normalize it and either map it to an existing tag or
send it for taxonomy review.
3.5 `parent_interests/{interestId}`
Stores a parent's or child's selected interests. Prefer child-specific
preferences when recommendations are intended for a particular child.

```
{
  "parentId": "parent_A",
  "childId": "child_A",
  "tagId": "songs",
  "preferenceSource": "PARENT_SELECTED",
  "weight": 1.0,
  "createdAt": "SERVER_TIMESTAMP",
  "updatedAt": "SERVER_TIMESTAMP"
}
```

Recommended document ID for a unique selection: `{childId}_{tagId}`.
This prevents duplicate selections for the same child/tag pair.
If the product later distinguishes a parent's own interests from a
child's learning interests, keep those preference scopes separate.
3.6 `content_sources/{sourceId}`
Canonical record for a YouTube channel, show/series, podcast feed, or
other source. One source document should exist regardless of how many
parents submit, save, or share it.

```
{
  "name": "Super Simple Songs",
  "normalizedName": "super simple songs",
  "sourceType": "YOUTUBE_CHANNEL",
  "platform": "YOUTUBE",
  "canonicalUrl": "https://www.youtube.com/@SuperSimpleSongs",
  "platformSourceId": "PLATFORM_ID_IF_AVAILABLE",
  "description": "Songs, nursery rhymes, movement, and early-learning concepts",
  "tagIds": ["songs", "nursery-rhymes", "language"],
  "originTypes": ["PREPOPULATED", "PARENT_SUBMITTED"],
  "catalogStatus": "ACTIVE",
  "assessmentStatus": "ASSESSED",
  "createdAt": "SERVER_TIMESTAMP",
  "updatedAt": "SERVER_TIMESTAMP",
  "lastVerifiedAt": "SERVER_TIMESTAMP"
}
```

Allowed `sourceType` values might include: - `YOUTUBE_CHANNEL` -
`SHOW` - `PODCAST_FEED` - `CREATOR` - `OTHER`
`originTypes` is provenance, not approval. If provenance needs detailed
attribution, use a separate `content_source_origins` collection rather
than growing an unbounded array.
For stronger deduplication, prefer a unique platform source ID or
normalized canonical URL. A display name alone is not a reliable unique
key.
3.7 `content_items/{contentItemId}`
Individual videos, podcast episodes, or playable items. This collection
is recommended even if the first release mainly recommends channels or
podcast feeds.

```
{
  "sourceId": "sss_001",
  "itemType": "VIDEO",
  "platform": "YOUTUBE",
  "title": "Example counting song",
  "canonicalUrl": "https://www.youtube.com/watch?v=EXAMPLE_ID",
  "platformItemId": "EXAMPLE_ID",
  "description": "Example item; replace with verified metadata",
  "durationSeconds": 120,
  "tagIds": ["songs", "counting"],
  "publishedAt": null,
  "catalogStatus": "ACTIVE",
  "createdAt": "SERVER_TIMESTAMP",
  "updatedAt": "SERVER_TIMESTAMP"
}
```

For podcast episodes, use `itemType: "PODCAST_EPISODE"` and preserve the
stable episode GUID from the RSS feed when available.
A channel/feed assessment must not be treated as an assessment of every
item in that source.
3.8 `content_assessments/{assessmentId}`
Append-oriented assessment records. Preserve previous assessments so
changes can be audited. Keep the latest assessment pointer/status on the
source or item for efficient reads.

```
{
  "targetType": "CONTENT_ITEM",
  "targetId": "video_001",
  "assessorType": "LLM",
  "assessmentStatus": "REVIEW_REQUIRED",
  "minAgeMonths": 24,
  "maxAgeMonths": 48,
  "suggestedTagIds": ["songs", "counting"],
  "learningGoals": ["Vocabulary", "Early counting"],
  "presentationStyle": "UNKNOWN",
  "safetyFindings": [],
  "evidenceLevel": "METADATA_ONLY",
  "evidenceReferences": [],
  "confidence": null,
  "limitations": ["No transcript or audiovisual review was available"],
  "modelName": "MODEL_NAME",
  "modelVersion": "MODEL_VERSION",
  "policyVersion": "content-policy-v1",
  "assessedAt": "SERVER_TIMESTAMP",
  "reviewedBy": null,
  "reviewedAt": null
}
```

Suggested statuses: - `PENDING` - `ASSESSED` - `REVIEW_REQUIRED` -
`APPROVED_FOR_RECOMMENDATION` - `REJECTED` - `STALE`
Do not interpret a model-generated confidence score as a calibrated
probability of safety unless it has been validated as such. Do not claim
safety from title/description alone.
3.9 `content_submissions/{submissionId}`
Tracks the parent's input and the agent's processing workflow.

```
{
  "submittedByParentId": "parent_A",
  "childId": "child_A",
  "inputText": "A new children's podcast",
  "inputUrl": null,
  "submissionType": "PODCAST",
  "status": "RECEIVED",
  "resolvedSourceId": null,
  "duplicateOfSourceId": null,
  "failureReason": null,
  "createdAt": "SERVER_TIMESTAMP",
  "updatedAt": "SERVER_TIMESTAMP"
}
```

Suggested processing states: - `RECEIVED` - `RESOLVING` -
`DUPLICATE_FOUND` - `AWAITING_EVIDENCE` - `ASSESSMENT_PENDING` -
`REVIEW_REQUIRED` - `READY_FOR_PARENT` - `FAILED`
A submission should not become approved just because processing
succeeded.
3.10 `parent_follows/{relationshipId}`
Stores parent-to-parent following relationships. This is different from
following/saving a channel.

```
{
  "followerParentId": "parent_C",
  "followedParentId": "parent_A",
  "status": "ACTIVE",
  "createdAt": "SERVER_TIMESTAMP"
}
```

Use a deterministic relationship ID such as
`{followerParentId}_{followedParentId}` if appropriate. Validate that a
parent cannot follow themselves. If the product supports
private/approval-required accounts, model pending follow requests
separately or use an explicit status.
3.11 `parent_content_relationships/{relationshipId}`
Stores a parent's own saved/following relationship with a source. This
is not a social share.

```
{
  "parentId": "parent_A",
  "sourceId": "sss_001",
  "relationshipType": "SAVED",
  "createdAt": "SERVER_TIMESTAMP"
}
```

Possible relationship types: - `SAVED` - `FOLLOWING_SOURCE` -
`DISMISSED`
Use one document per parent/source/relationship type. A save must not
automatically publish content to followers.
3.12 `parent_content_shares/{shareId}`
Stores explicit shares with visibility rules.

```
{
  "parentId": "parent_A",
  "sourceId": "sss_001",
  "contentItemId": null,
  "visibility": "FOLLOWERS",
  "shareText": "We enjoyed these songs",
  "status": "ACTIVE",
  "createdAt": "SERVER_TIMESTAMP",
  "updatedAt": "SERVER_TIMESTAMP"
}
```

Possible visibility: - `PRIVATE` - `FOLLOWERS` - `PUBLIC`
A share can point to a source or an individual item. Validate that the
share target exists and is eligible for sharing. Do not expose a child's
viewing history through this record.
3.13 `child_catalog_entries/{entryId}`
Stores child-specific saved/approved content. This is the authoritative
parent/guardian decision for a child.

```
{
  "parentId": "parent_A",
  "childId": "child_A",
  "sourceId": "sss_001",
  "contentItemId": null,
  "status": "SAVED_PENDING_APPROVAL",
  "approvedBy": null,
  "approvedAt": null,
  "restrictions": [],
  "createdAt": "SERVER_TIMESTAMP",
  "updatedAt": "SERVER_TIMESTAMP"
}
```

Suggested statuses: - `SAVED_PENDING_APPROVAL` - `APPROVED` -
`REJECTED` - `REVOKED`
If the product allows channel-level approval, define clearly whether
that means only the channel landing page can be recommended or whether
individual future uploads are eligible. For a conservative
child-controlled product, prefer item-level eligibility and explicit
approval, especially for young children.
3.14 Optional: `recommendation_events/{eventId}`
Use an event collection or a dedicated event pipeline for analytics.
Avoid unbounded arrays on a parent or child document.

```
{
  "parentId": "parent_A",
  "childId": "child_A",
  "candidateId": "sss_001",
  "candidateType": "CONTENT_SOURCE",
  "discoveryCluster": "GLOBAL",
  "eventType": "RECOMMENDATION_IMPRESSION",
  "tagIdsMatched": ["songs"],
  "createdAt": "SERVER_TIMESTAMP"
}
```

Example `eventType` values: `IMPRESSION`, `OPEN`, `SAVE`, `DISMISS`,
`APPROVE`, `PLAYBACK_STARTED`, `PLAYBACK_COMPLETED`. Minimize collection
of child behavior data and apply retention and privacy controls.

3.15 `source_signals/{sourceId}` (added by decision record 0008; not in the original spec)
Precomputed recommendation signals for one `content_sources` document, written only by the BigQuery ML batch job and read by the recommender.
Derived from public catalog text only; no parent, child or behaviour data. Never decides eligibility.

```
{
  "sourceId": "youtube_UC...",
  "neighbors": [ { "sourceId": "youtube_UC...", "similarity": 0.93 } ],
  "clusterId": 3,
  "clusterLabel": "Stories & Social-Emotional Learning",
  "modelVersion": "text-embedding-005",
  "computedAt": "TIMESTAMP"
}
```
3.16 `adk-session/{appName}/sessions/{sessionId}` (added by decision record 0014; not in the original spec)
The Aram Agent's conversations, written and read only by ADK's `FirestoreSessionService` through `AgentSessionStore`. `appName` is `aram-agent`. The ADK library owns the exact layout
(a session document with `userId`, `state`, update time, and a `user-event` subcollection of events); we only rely on the following.

```
{
  "userId": "<the signed-in parent's Firebase uid>",   // ownership: a session is only ever opened with the caller's own uid
  "state": { "lastCandidates": "<JSON list of the last answer's candidate cards>" },
  "events": [ { "author": "user|aram_agent", "content": { "role": "user|model", "text": "..." }, "timestamp": ... } ]
}
```
Holds plain chat text only (what the parent typed and what Aram answered), no tool calls or tool results. Retention is 7 days since last activity, enforced by deletion when the
parent next opens the agent (no TTL in the library). Parent-typed text: never exported to BigQuery (`AnalyticsPrivacyTest`), and not part of any ML input. Only the backend reads it
(Admin SDK); there are no client-side rules for it.

3.17 `book_signals/{bookId}` (added by decision record 0017; not in the original spec)
Precomputed similarity of one curated book (from `books.json`) to catalog sources, written only by the BigQuery ML batch job and read by the
book recommender. Derived from public text only (title, author, our description, topic names); no parent, child or behaviour data. It only ranks:
the book's age range is still a hard filter and a signal never makes anything eligible.

```
{
  "bookId": "lets-say-our-numbers",
  "neighbors": [ { "sourceId": "youtube_UC...", "similarity": 0.69 } ],
  "modelVersion": "text-embedding-005",
  "computedAt": "TIMESTAMP"
}
```
4. Global and social discovery: do not create duplicate catalogs
There is one canonical catalog. Global and social are
candidate-generation paths, not separate copies of content.
Global discovery
Candidate sources: - Pre-populated sources. - New sources submitted by
any parent, after resolution and appropriate assessment.
Candidate logic: 1. Read the child's age and selected tags. 2. Find
sources/items whose tags overlap the selected interests. 3. Apply age,
assessment, source-status, family-restriction, and other eligibility
checks. 4. Rank the eligible candidates by relevance. 5. Show
recommendations with transparent reasons.
Social discovery
Candidate sources: - Content explicitly shared by parents that the
current parent follows. - Only shares whose visibility permits access.
Candidate logic: 1. Read the current parent's active follow
relationships. 2. Retrieve active shares from followed parents. 3. Check
visibility and access permissions. 4. Resolve shares to canonical
source/item IDs. 5. Deduplicate candidates. 6. Apply the same age and
safety eligibility rules used for global discovery. 7. Rank by tag
relevance, recency, and other validated signals.
Combined feed
A combined feed can union the two candidate lists and deduplicate by
canonical item/source ID. Preserve the reasons for each candidate so the
UI can say: - "Matches your selected interests" - "Shared by a parent
you follow" - "Matches your interests and was shared by two parents you
follow"
If separate Global and Following tabs are preferred, both tabs should
still use the same canonical content and eligibility logic.
Example
Parent A selects `songs`, `animals`, and `early-math`. Parent A follows
Parent B and Parent C.
Source/item Tags Provenance/share Global Social
candidate? candidate?
Super Simple songs, Pre-populated; Yes Only if B
Songs language saved by A and B explicitly
shared it
Numberblocks early-math, Submitted by B Yes Only if B
counting explicitly
shared it
Animal podcast animals, Explicitly shared Yes, if Yes
nature by B cataloged and
eligible
Counting video early-math, Explicitly shared Yes, if Yes
counting by C cataloged and
eligible
Unrelated crafts Shared by B No match unless Not relevant to
craft channel another rule selected tags
applies
Unassessed animals Shared by C Not eligible for Not eligible for
video recommendation recommendation
if assessment is if assessment is
required required
Being followed, saved, or popular does not itself imply approval.
5. BigQuery dataset and table design
Use a dataset such as `children_content_analytics`. Dataset/table names
are examples; select region and naming conventions consistent with the
Firestore export, data residency requirements, and project setup.
BigQuery should be an analytical representation of Firestore data plus
event data, not the primary transactional store.
5.1 Recommended analytical tables
BigQuery table Grain Purpose
`dim_parents` One row per parent Pseudonymous parent
analytics attributes;
minimize PII
`dim_children` One row per Age band and minimal
child/profile permitted attributes
`dim_categories` One row per category Category taxonomy
`dim_tags` One row per tag Tag taxonomy
`dim_content_sources` One row per canonical Channel/feed/show
source metadata
`dim_content_items` One row per Individual item
video/episode metadata
`fact_content_assessments` One row per assessment Assessment history and
version evidence quality
`bridge_content_tags` One row per content/tag Many-to-many
association content/tag
relationship
`fact_parent_interests` One row per Interest signals
parent/child/tag
selection version
`fact_parent_follows` One row per follow Social graph analytics
relationship/version with privacy controls
`fact_content_shares` One row per share Shared-content
discovery analytics
`fact_child_catalog_entries` One row per Save and approval
catalog-entry behavior
state/version
`fact_recommendation_events` One row per event Impressions, opens,
saves, dismissals
`fact_playback_events` One row per permitted Aggregate engagement
playback event analysis, subject to
privacy policy
Prefer append-only event facts for events. For dimensions that change,
decide whether you need only the latest snapshot or historical versions.
BigQuery tables should not be a direct copy of every sensitive Firestore
field.
5.2 Example: `dim_content_sources`

```
CREATE TABLE IF NOT EXISTS `YOUR_PROJECT.children_content_analytics.dim_content_sources` (
  source_id STRING NOT NULL,
  name STRING,
  normalized_name STRING,
  source_type STRING,
  platform STRING,
  canonical_url STRING,
  platform_source_id STRING,
  tag_ids ARRAY<STRING>,
  catalog_status STRING,
  assessment_status STRING,
  created_at TIMESTAMP,
  updated_at TIMESTAMP,
  last_verified_at TIMESTAMP
);
```

`YOUR_PROJECT` is a placeholder. Replace it with the actual Google Cloud
project ID. BigQuery primary-key declarations are not enforced like
relational database constraints; enforce uniqueness in the ingestion
pipeline and monitor duplicates.
5.3 Example: `dim_content_items`

```
CREATE TABLE IF NOT EXISTS `YOUR_PROJECT.children_content_analytics.dim_content_items` (
  content_item_id STRING NOT NULL,
  source_id STRING,
  item_type STRING,
  platform STRING,
  title STRING,
  canonical_url STRING,
  platform_item_id STRING,
  duration_seconds INT64,
  tag_ids ARRAY<STRING>,
  published_at TIMESTAMP,
  catalog_status STRING,
  created_at TIMESTAMP,
  updated_at TIMESTAMP
);
```

5.4 Example: `fact_content_assessments`

```
CREATE TABLE IF NOT EXISTS `YOUR_PROJECT.children_content_analytics.fact_content_assessments` (
  assessment_id STRING NOT NULL,
  target_type STRING,
  target_id STRING,
  assessment_status STRING,
  min_age_months INT64,
  max_age_months INT64,
  suggested_tag_ids ARRAY<STRING>,
  learning_goals ARRAY<STRING>,
  safety_findings ARRAY<STRING>,
  evidence_level STRING,
  limitations ARRAY<STRING>,
  model_name STRING,
  model_version STRING,
  policy_version STRING,
  assessed_at TIMESTAMP,
  reviewed_at TIMESTAMP
)
PARTITION BY DATE(assessed_at);
```

5.5 Example: `bridge_content_tags`
This table makes tag analysis easier than relying only on arrays in
source tables.

```
CREATE TABLE IF NOT EXISTS `YOUR_PROJECT.children_content_analytics.bridge_content_tags` (
  target_type STRING,
  target_id STRING,
  tag_id STRING,
  association_source STRING,
  confidence FLOAT64,
  valid_from TIMESTAMP,
  valid_to TIMESTAMP
);
```

`association_source` may be `CURATED`, `LLM_PROPOSED`, or
`HUMAN_REVIEWED`. Do not treat LLM-proposed tags as verified without the
configured approval policy.
5.6 Example: `fact_recommendation_events`

```
CREATE TABLE IF NOT EXISTS `YOUR_PROJECT.children_content_analytics.fact_recommendation_events` (
  event_id STRING,
  parent_id STRING,
  child_id STRING,
  candidate_id STRING,
  candidate_type STRING,
  discovery_cluster STRING,
  event_type STRING,
  matched_tag_ids ARRAY<STRING>,
  event_timestamp TIMESTAMP
)
PARTITION BY DATE(event_timestamp)
CLUSTER BY discovery_cluster, candidate_type, event_type;
```

Use `discovery_cluster` values such as `GLOBAL` and `SOCIAL`. Do not
store unnecessary direct identifiers or sensitive child data in
analytics tables. Consider pseudonymous IDs, restricted access,
retention limits, and aggregation thresholds.
5.7 Example: `fact_parent_follows`

```
CREATE TABLE IF NOT EXISTS `YOUR_PROJECT.children_content_analytics.fact_parent_follows` (
  follower_parent_id STRING,
  followed_parent_id STRING,
  status STRING,
  created_at TIMESTAMP,
  updated_at TIMESTAMP
);
```

Access to social-graph data should be tightly controlled. Do not make
this table a source of public follower lists unless the product
explicitly permits it.
6. Example BigQuery query for global candidates
This is an analytical example. In the live app, Firestore or a backend
service should enforce authoritative current eligibility and parent
approval.
Assume `selected_tags` is a temporary table or query input containing
the child's selected tag IDs.

```
WITH selected_tags AS (
  SELECT tag_id
  FROM UNNEST(['songs', 'animals', 'early-math']) AS tag_id
),
eligible_sources AS (
  SELECT
    s.source_id,
    s.name,
    s.tag_ids,
    a.min_age_months,
    a.max_age_months
  FROM `YOUR_PROJECT.children_content_analytics.dim_content_sources` s
  JOIN `YOUR_PROJECT.children_content_analytics.fact_content_assessments` a
    ON a.target_id = s.source_id
   AND a.target_type = 'CONTENT_SOURCE'
  WHERE s.catalog_status = 'ACTIVE'
    AND s.assessment_status = 'ASSESSED'
    AND a.assessment_status = 'APPROVED_FOR_RECOMMENDATION'
    AND a.min_age_months <= 24
    AND a.max_age_months >= 24
)
SELECT
  e.source_id,
  e.name,
  COUNT(DISTINCT t.tag_id) AS matched_tag_count,
  ARRAY_AGG(DISTINCT t.tag_id) AS matched_tags
FROM eligible_sources e
JOIN UNNEST(e.tag_ids) AS source_tag_id
  ON TRUE
JOIN selected_tags t
  ON t.tag_id = source_tag_id
GROUP BY e.source_id, e.name
ORDER BY matched_tag_count DESC, e.name
LIMIT 50;
```

Important implementation note: if assessment history is append-only,
first select the latest valid assessment per target (for example using
`QUALIFY ROW_NUMBER()` ordered by `assessed_at DESC`). Do not join all
historical assessments and accidentally duplicate candidate rows. In
production, apply current policy and child-specific approval/restriction
checks before returning playable content.
7. Example BigQuery query for social candidates
The query below illustrates how social shares intersect with selected
tags. It assumes the analytics tables contain current follow/share state
and current source tags.

```
WITH selected_tags AS (
  SELECT tag_id
  FROM UNNEST(['songs', 'animals', 'early-math']) AS tag_id
),
followed_parents AS (
  SELECT followed_parent_id
  FROM `YOUR_PROJECT.children_content_analytics.fact_parent_follows`
  WHERE follower_parent_id = 'parent_A'
    AND status = 'ACTIVE'
),
social_candidates AS (
  SELECT DISTINCT
    sh.source_id,
    sh.parent_id AS shared_by_parent_id
  FROM `YOUR_PROJECT.children_content_analytics.fact_content_shares` sh
  JOIN followed_parents f
    ON f.followed_parent_id = sh.parent_id
  WHERE sh.status = 'ACTIVE'
    AND sh.visibility = 'FOLLOWERS'
),
tag_matches AS (
  SELECT
    s.source_id,
    s.name,
    COUNT(DISTINCT t.tag_id) AS matched_tag_count,
    ARRAY_AGG(DISTINCT t.tag_id) AS matched_tags
  FROM `YOUR_PROJECT.children_content_analytics.dim_content_sources` s
  JOIN UNNEST(s.tag_ids) AS source_tag_id
    ON TRUE
  JOIN selected_tags t
    ON t.tag_id = source_tag_id
  GROUP BY s.source_id, s.name
)
SELECT
  c.source_id,
  m.name,
  ARRAY_AGG(DISTINCT c.shared_by_parent_id) AS shared_by_parents,
  m.matched_tag_count,
  m.matched_tags
FROM social_candidates c
JOIN tag_matches m USING (source_id)
GROUP BY c.source_id, m.name, m.matched_tag_count, m.matched_tags
ORDER BY m.matched_tag_count DESC, m.name
LIMIT 50;
```

This is an analytical illustration, not a complete production
authorization query. The live service must verify share visibility and
follow status using authoritative current data. If the schema supports
individual-item shares, union item-level and source-level candidates
while preserving their distinct targets.
8. Candidate merging and ranking
The global and social candidate paths should return a common shape:

```
{
  "candidateId": "sss_001",
  "candidateType": "CONTENT_SOURCE",
  "discoveryReasons": [
    "MATCHED_SELECTED_TAG",
    "SHARED_BY_FOLLOWED_PARENT"
  ],
  "matchedTagIds": ["songs"],
  "sharedByParentIds": ["parent_B"],
  "assessmentStatus": "APPROVED_FOR_RECOMMENDATION",
  "ageEligible": true
}
```

The backend should: 1. Generate candidates from the global path. 2.
Generate candidates from the social path. 3. Merge and deduplicate by
canonical source/item ID. 4. Preserve all applicable discovery reasons.
5. Apply eligibility rules using current authoritative data. 6. Rank
only eligible candidates. 7. Return clear reasons to the parent.
A simple MVP ranking score could be:

```
score =
  (tag_overlap_weight * normalized_tag_overlap)
  + (social_relevance_weight * social_signal)
  + (curation_weight * verified_curation_signal)
  + (freshness_weight * freshness_signal)
```

The coefficients are product decisions to test; this is not a safety
score. Do not let popularity or social signals override safety, age fit,
access controls, or parent restrictions.
9. BigQuery ML roadmap
Phase 1: no ML required

* Use Firestore queries and backend filtering.
* Match selected tags to source/item tags.
* Apply age and assessment eligibility.
* Deduplicate global and social candidates.
* Rank by transparent deterministic scoring.

Phase 2: analytics

* Track recommendation impressions, opens, saves, dismissals, and
approved playback events.
* Evaluate tag relevance and recommendation quality.
* Measure catalog coverage and duplicate rates.
* Use appropriate privacy safeguards and retention limits.

Phase 3: ML-assisted ranking

* Train and validate a ranking/recommendation model only after
sufficient data exists.
* Use BigQuery ML where the problem and data support it.
* Compare the model against the rules-based baseline.
* Monitor relevance, bias, and age-group performance.
* Keep the model out of the authoritative safety/approval decision.

Related-tag suggestions can initially be curated or derived from tag
co-occurrence. Avoid treating correlations in parent behavior as proof
that tags are educationally equivalent.
10. Firestore indexes, security, and operational considerations
Indexing
Create composite indexes only for queries actually used, such as: -
Content sources by `catalogStatus` and `assessmentStatus`. - Submissions
by `submittedByParentId` and `status`. - Child catalog entries by
`childId` and `status`. - Parent follows by `followerParentId` and
`status`. - Shares by `parentId`, `visibility`, and `status`. - Parent
interests by `childId` and `tagId`.
Firestore may require additional composite indexes based on the exact
query. Use the index creation links in development errors and test query
costs.
Security

* Enforce parent/guardian access in Firestore Security Rules and/or
trusted backend authorization.
* Never trust a client-supplied `parentId`, `childId`, approval
status, or assessment result without authorization.
* Use a trusted backend for content submission, source resolution, AI
evaluation, and approval state transitions.
* Keep service-account credentials out of the client app.
* Restrict BigQuery datasets and tables by least privilege.
* Avoid putting children's names, dates of birth, or detailed viewing
histories into ML features unless strictly necessary and approved by
your privacy design.
* Define data deletion, retention, and parental consent workflows
appropriate to the jurisdictions where the application operates.

Cost and scale

* Avoid scanning the entire content catalog on every request.
* Use Firestore indexes for operational lookups and a
curated/tag-indexed candidate strategy.
* Avoid unbounded arrays for relationships that can grow indefinitely.
* Use pagination and limits for feeds.
* Export analytics asynchronously or through a managed pipeline.
* Use BigQuery for batch analytics and model development rather than
adding query latency to every app request.

Playback authorization
A saved channel is not the same as authorization to play every item on
that channel. Define whether the product approves sources, individual
items, or both. At playback time, check current status and restrictions.
Do not rely on a previously generated recommendation list as the
authorization decision.
Playback availability (decision record 0015): approval is not a promise that the audio or
video still exists. For podcasts the backend asks the show's audio host for the newest
episode (a clear 403/404/410 or an error page means unavailable; timeouts and 5xx mean
unknown) and hides a show whose audio is clearly unavailable from Kid Space and from the
agent. Results are kept in memory only; nothing is stored.
Curated books (decision records 0016, 0017): the Parent Space shows picture-book suggestions with a link to buy. They come from a
reviewed JSON file (`books.json`, ages in months, tags from the taxonomy). The books themselves are not in Firestore; the only stored
derivative is `book_signals` (3.17), computed by the recommendation engine from public text. There is no per-family data and no BigQuery export of families. Only books whose age range includes the child's age are offered, ranked by the parent's
chosen interests. Buy links must be https on an allowed store. Kid Space never shows books or purchase links.
11. Initial seed-data examples
Categories

```
[
  {"id": "songs-music", "name": "Songs & Music"},
  {"id": "language-literacy", "name": "Language & Early Literacy"},
  {"id": "math-counting", "name": "Math & Counting"},
  {"id": "science-discovery", "name": "Science & Discovery"},
  {"id": "nature-animals", "name": "Nature & Animals"},
  {"id": "stories-imagination", "name": "Stories & Imagination"},
  {"id": "social-emotional", "name": "Social-Emotional Learning"},
  {"id": "art-creativity", "name": "Art & Creativity"}
]
```

Tags

```
[
  {"id": "songs", "name": "Songs", "categoryId": "songs-music"},
  {"id": "nursery-rhymes", "name": "Nursery Rhymes", "categoryId": "songs-music"},
  {"id": "language", "name": "Language", "categoryId": "language-literacy"},
  {"id": "phonics", "name": "Phonics", "categoryId": "language-literacy"},
  {"id": "early-math", "name": "Early Math", "categoryId": "math-counting"},
  {"id": "counting", "name": "Counting", "categoryId": "math-counting"},
  {"id": "animals", "name": "Animals", "categoryId": "nature-animals"},
  {"id": "nature", "name": "Nature", "categoryId": "nature-animals"},
  {"id": "stories", "name": "Stories", "categoryId": "stories-imagination"},
  {"id": "friendship", "name": "Friendship", "categoryId": "social-emotional"}
]
```

Example canonical source

```
{
  "id": "sss_001",
  "name": "Super Simple Songs",
  "sourceType": "YOUTUBE_CHANNEL",
  "platform": "YOUTUBE",
  "canonicalUrl": "https://www.youtube.com/@SuperSimpleSongs",
  "tagIds": ["songs", "nursery-rhymes", "language"],
  "originTypes": ["PREPOPULATED"],
  "catalogStatus": "ACTIVE",
  "assessmentStatus": "REVIEW_REQUIRED"
}
```

The status is deliberately conservative in this template. Before
production, populate real assessments based on available evidence and
your review policy. Do not infer that a whole channel is safe based on
its name.
12. Implementation checklist
Firestore

* Create the collections described in Section 3.
* Choose stable IDs and canonical URL/platform-ID deduplication.
* Seed categories and tags.
* Create parent/child-specific interest records.
* Implement content submission and AI assessment state
transitions.
* Implement explicit parent saves and shares.
* Implement child-specific approval and revocation.
* Configure Security Rules, backend authorization, and indexes.

BigQuery

* Create the `children_content_analytics` dataset in the selected
region.
* Create dimension, bridge, and fact tables described in Section
   5. 
* Build a reliable Firestore-to-BigQuery export/streaming
pipeline.
* Deduplicate by canonical IDs and select current assessment state
correctly.
* Add partitioning/clustering based on actual query patterns.
* Apply least-privilege access and retention policies.
* Build analytics and validate recommendation quality before
adding ML.

AI agent

* Resolve the submitted name/URL.
* Check for an existing source before creating a new one.
* Prefer existing tags; propose new tags only when necessary.
* Store evidence level, limitations, model version, and policy
version.
* Route insufficient evidence to review rather than claiming
safety.
* Never approve playback solely because an LLM assessment is
positive.

Recommendation pipeline

* Implement global candidate generation.
* Implement social candidate generation from explicit permitted
shares.
* Merge/deduplicate candidates.
* Apply eligibility before ranking.
* Explain recommendation reasons to the parent.
* Keep recommendation and approval states separate.

13. Final architecture decision
Use one canonical content catalog in Firestore. Use relationship
records to track parent saves/follows and explicit shares. Use a
separate child catalog for parent-approved content. Treat Global
Discovery and Social Discovery as two candidate-generation paths into
one shared eligibility and ranking pipeline. Replicate appropriate
operational data and events into BigQuery for analytics and later ML.
This keeps the system normalized enough to avoid duplicate content,
flexible enough to add social discovery, and conservative enough for a
parent-controlled children's application.
Before production: validate Firestore query patterns and Security
Rules, BigQuery region/data residency, platform API and playback terms,
child-data privacy obligations, and the content assessment policy. This
document is an implementation blueprint, not a substitute for those
reviews.
