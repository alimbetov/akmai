# Post-Phase-B Defect Remediation Ledger

Branch: `fix/post-phase-b-defect-remediation`

Purpose: accumulate defects found by repeated deep audits and close them with reproducible tests and exact-SHA CI evidence.

Canonical remediation implementation contract: `docs/audit/post-phase-b-remediation-technical-spec.md`. All D01–D72 code changes, regression tests and verification evidence MUST conform to that specification.

## Audit cadence

- Audit 1/10: completed — initial deep audit after Phase B
- Audit 2/10: completed — generation-publication model and citation failure-path audit
- Audit 3/10: completed — retention fencing, context budgeting and reranker isolation audit
- Audit 4/10: completed — process simulation, queue/load envelope and cross-pipeline contract audit
- Audit 5/10: completed — system-analysis and architecture-contract review
- Audit 6/10: completed — previously-unreviewed chunking, ingestion runtime, retention queue and executor lifecycle audit
- Audit 7/10: completed — SQL/data-volume, Unicode, malformed-corpus, identity-serialization and distributed-clock audit
- Audit 8/10: completed — invariant-driven architecture review (authority, semantic preservation, boundedness and verification architecture)
- Audit 9/10: completed — counterexample-driven review of SQL semantics, distributed heartbeats, multilingual parsing, degraded retrieval outcomes and readiness
- Audit 10/10: completed — destructive QA / boundary, mutation-oracle and malformed-dependency review; remediation closeout and final exact-SHA regression gate remain pending
- A defect is never removed from this ledger. It moves through `OPEN -> IMPLEMENTING -> FIXED -> VERIFIED`.
- `VERIFIED` requires an automated regression test and exact-SHA successful CI.

## Severity

- P0: data corruption / security compromise / unrecoverable consistency failure
- P1: major correctness, availability, retrieval-integrity or production blocker
- P2: medium correctness/performance/operability defect
- P3: hardening / maintainability / documentation gap

## Audit 1/10 findings

| ID | Sev | Area | Defect | Required remediation | Verification | Status |
| --- | --- | --- | --- | --- | --- | --- |
| D01 | P0 | ingestion/vector | Failed ingestion can leave orphan physical vectors when vector add partially succeeds before generation manifest is saved | generation staging + compensation/reconciliation | fault-injection integration test (`4d255816`) | IMPLEMENTING |
| D02 | P0 | retrieval/lifecycle | Retrieval does not enforce READY/current generation visibility | published-generation visibility fence on vector/lexical/identifier reads | INGEST_FAILED generation invisible in all strategies | OPEN |
| D03 | P0 | ingestion | Re-ingestion deletes previous READY generation before replacement is successfully published | non-destructive generation replacement / atomic publication switch | failing replacement contract (`4d255816`) | IMPLEMENTING |
| D04 | P1 | fusion | Identifier snippet can become canonical representative for a chunk and hide full chunk payload/provenance | deterministic representative priority / canonical payload | mixed identifier+semantic query preserves canonical text | OPEN |
| D05 | P1 | retrieval | Identifier-only queries return identifier context but do not resolve canonical chunk/neighbors | canonical resolution after identifier hit | identifier-only acceptance | OPEN |
| D06 | P1 | PostgreSQL FTS | RU/EN queries build russian/english tsvector at query time while indexed generated vector is `simple` | language-specific expression/generated indexes | EXPLAIN/integration index contract | OPEN |
| D07 | P1 | retrieval/runtime | Retrieval plan has no overall/strategy deadlines; blocking dependency can stall request indefinitely | bounded per-strategy/request deadlines + cancellation/fallback | timeout isolation tests | OPEN |
| D08 | P1 | concurrency | Shared bounded executor uses CallerRunsPolicy for latency-sensitive retrieval, allowing work on HTTP thread under saturation | retrieval-specific reject/fallback policy | saturation isolation test | OPEN |
| D09 | P1 | retention | Retention shutdown can stop heartbeat executor before queued workers start; heartbeat scheduling failure bypasses cleanup/finally and leaks reservations | safe shutdown ordering + guarded heartbeat startup | shutdown race test | OPEN |
| D10 | P1 | RAG security | Retrieved document text is inserted into prompt without explicit untrusted-data boundary | prompt-injection resistant system/context framing + test corpus | malicious-context acceptance test | OPEN |
| D11 | P1 | citations | Non-empty generated answers with zero citations are accepted as valid | require citation integrity for grounded answers | uncited answer test | OPEN |
| D12 | P2 | citations | Invalid citation sanitizer recognizes flexible whitespace but removes only exact single-space form | regex-based invalid marker removal | whitespace variant tests | OPEN |
| D13 | P2 | provenance | Page metadata contract is inconsistent: `page`, `pageNumber`, `pageFrom` | typed/canonical provenance metadata | source mapping tests | OPEN |
| D14 | P2 | expansion | Neighbor expansion drops source/page/domain provenance | preserve canonical projection metadata during expansion | neighbor citation test | OPEN |
| D15 | P1 | multilingual | Ingestion accepts arbitrary language spelling/case while retrieval filters canonical lower-case codes | canonicalize/validate KK/RU/EN/ZH at write boundary | RU/russian/ru normalization tests | OPEN |
| D16 | P2 | multilingual | QueryLanguageDetector can classify Kazakh text containing only shared Cyrillic letters as Russian | stronger language routing/fallback | ambiguous KK corpus tests | OPEN |
| D17 | P1 | API/security | No request size/field length boundaries; DB VARCHAR constraints are not mirrored at API boundary | request/body/token/metadata limits and validation | oversized request tests | OPEN |
| D18 | P1 | security | HTTP ingestion and RAG endpoints have no authentication/authorization | explicit local-only profile or Spring Security/API auth | unauthorized request tests | OPEN |
| D19 | P2 | identity | `VectorIdentity.physicalId` and runtime PersistenceCoordinator use incompatible vector ID schemes | one canonical vector identity implementation | identity contract test | OPEN |

## Audit 2/10 findings

| ID | Sev | Area | Defect | Required remediation | Verification | Status |
| --- | --- | --- | --- | --- | --- | --- |
| D20 | P0 | ingestion/publication | Lifecycle/vector data is generation-scoped, but `knowledge_search_projection` and `document_identifier` are not. New lexical projections and identifiers cannot be staged alongside generation N+1 without deleting or overwriting generation N before publication, so a true atomic multi-modality publication switch is impossible with the current schema | add generation identity to canonical projections and identifiers; stage rows per generation; make vector/lexical/identifier reads resolve only the published generation; retire old generation only after successful publication; compensate failed staged generation | integration contract: failed N+1 leaves N vector + lexical + identifier results unchanged and N+1 invisible; successful publish switches all retrieval modalities to N+1 together | OPEN |
| D21 | P2 | citations/availability | `CitationValidator` parses the numeric body of every `[SOURCE n]` marker with `Integer.parseInt`; a model response containing an out-of-range integer marker throws `NumberFormatException` and fails the whole RAG request instead of treating the citation as invalid | parse citation numbers without overflow (bounded parse or guarded exception) and sanitize/record oversized markers as invalid | huge citation marker such as `[SOURCE 999999999999999999999]` does not throw and is removed/reported invalid | OPEN |


## Audit 3/10 findings

| ID | Sev | Area | Defect | Required remediation | Verification | Status |
| --- | --- | --- | --- | --- | --- | --- |
| D22 | P1 | retention/concurrency | `ChunkRetentionService` checks `isCurrentClaim` before vector deletion and again afterwards, but then deletes identifiers, projections and generation metadata without another fencing check. If the lease expires or the claim is replaced after the second check, a stale worker can continue destructive cleanup and only discover loss of ownership when `markDeleted` fails | make destructive cleanup lease-fenced through the entire critical section; re-check/atomically fence immediately before destructive tail, and ensure stale workers cannot mutate SQL/vector state after claim loss | race test that forces claim loss after vector phase but before SQL cleanup and proves stale worker performs no further destructive mutations | OPEN |
| D23 | P2 | RAG/context budget | `ContextBudget` counts only `hit.text()`, while `ContextAssembler` adds source labels and metadata fields (`documentId`, `chunkId`, `source`, `language`, `sectionPath`, `page`) for every chunk. The assembled context can therefore exceed `contextMaxTokens` even when the budget reports it within limit | budget the exact serialized context envelope, or reserve deterministic overhead per source plus prompt framing | test with long metadata / many chunks proving assembled context stays within configured token budget | OPEN |
| D24 | P2 | reranker/runtime | Reranker timeout uses `Future.get(timeout)` + `cancel(true)`, but the single reranker worker can remain blocked inside `EmbeddingModel.embed()` if the client ignores interruption. One hung embed can monopolize the only worker and force later reranks into repeated timeout/rejection fallback until the underlying call returns | enforce transport-level embedding timeout/cancellation, isolate or rotate poisoned workers, and bound queued rerank work independently of candidate count | blocking scorer test proving one timed-out request cannot prevent a later rerank from executing successfully | OPEN |


## Audit 4/10 findings

| ID | Sev | Area | Defect | Required remediation | Verification | Status |
| --- | --- | --- | --- | --- | --- | --- |
| D25 | P1 | retrieval/identifier scoping | Identifier-dependent vector/lexical steps receive an empty `RetrievalContext` when exact identifier lookup misses. Both strategies interpret an empty document set as “unscoped global search”, so a query for an unknown contract/order/etc. can silently broaden from identifier-scoped retrieval to the entire corpus | distinguish “no scope requested” from “required scope resolved to zero documents”; fail closed or use an explicit configured fallback policy for identifier-bearing queries | unknown identifier + semantic text must not execute unrestricted vector/lexical retrieval; multi-identifier tests cover partial misses | OPEN |
| D26 | P2 | retrieval/reference amplification | `ReferenceRetrievalStrategy` executes one identifier-index query per distinct textual reference, up to `referenceLimit` per reference step, and each lookup can return up to `identifierLimit` candidates. With 8 query units and defaults 20×10 this permits up to 160 identifier SQL lookups and 1,600 pre-fusion identifier candidates, plus projection lookups, from one user question | batch reference resolution, cap total resolved targets per step/request, deduplicate before database round-trips, and expose amplification metrics | query-count integration test and bounded-cardinality test for max-segment/max-reference input | OPEN |
| D27 | P1 | retrieval/reference correctness | The production reference pipeline has no real producer for the keys consumed by `ReferenceRetrievalStrategy`: `CrossReferenceExtractor` emits forms such as `статья/article/section/clause 48`, while the ingestion `IdentifierExtractor` only indexes business/document identifiers captured as their value token. Existing reference tests manually synthesize `references` and `DocumentIdentifier` rows that normal ingestion cannot create | define a typed canonical cross-reference identity and index it during ingestion; make extractor, normalizer, identifier storage and reference resolver share that contract | end-to-end Testcontainers test that ingests a source containing a real cross-reference and a target containing the referenced article/section, then resolves it without manually inserted identifier rows | OPEN |
| D28 | P1 | JDBC/concurrency | `DocumentOperationLock` holds a DataSource connection for the full ingestion/retention critical section, while code inside that section uses `JdbcTemplate`/pgvector and therefore needs additional connections from the same pool. If concurrent document locks consume the pool, every lock holder can block waiting for a second connection and no operation can progress to release its lock | avoid holding an application-pool connection solely for session advisory locking, use a dedicated lock datasource/connection budget or a single-connection transactional/fencing design, and enforce a concurrency invariant against pool capacity | Hikari integration test with a deliberately small pool and concurrent different-document operations proves no connection-starvation cycle | OPEN |
| D29 | P1 | RAG/runtime | Retrieval and reranking can be given deadlines, but the final synchronous `chatClient.prompt().call().content()` stage has no application-level request deadline/cancellation/fallback contract in this service. A slow or wedged generation call can therefore dominate end-to-end request latency after retrieval has completed | add an explicit answer-generation deadline and transport timeout, cancellation/isolation, and a deterministic fallback/error contract | blocking/fault-injected chat model test proves `ask()` returns or fails within the configured end-to-end deadline | OPEN |
| D30 | P2 | metadata/retrieval | Request metadata permits null values; those values are persisted in `metadata_json`, while `RetrievalHit` canonicalizes metadata with `Map.copyOf`, which rejects null keys/values. A document containing a null metadata value can therefore make a retrieval branch fail when its projection metadata is converted to a hit | validate/canonicalize metadata at ingestion and/or sanitize nulls before constructing retrieval hits; define supported metadata value types | ingest metadata containing a null value and prove vector/lexical retrieval remains deterministic rather than throwing/dropping the branch | OPEN |

### Audit 4 simulation / capacity evidence

These are stress-model results, not production telemetry.

- Query decomposition permits up to 8 units. An identifier-bearing unit can create 4 retrieval steps, giving a structural maximum of 32 retrieval tasks per request. With 8 workers, a simplified service-demand model yields an executor-only saturation envelope of about 5 max-fanout requests/s at 50 ms average task time, 2.5 requests/s at 100 ms, and 1 request/s at 250 ms, before database/model overhead.
- For identifier-scoped queries, if the independent per-identifier resolution miss rate were 10%, the probability that at least one of 8 identifier units enters the current unintended global fallback path is `1 - 0.9^8 ≈ 56.95%`. At a 5% miss rate it is about 33.66%. This quantifies D25 sensitivity; the miss rates themselves are illustrative.
- The reference-stage upper bound from the current defaults is deterministic: up to 8 reference steps × 20 identifier lookups = 160 identifier SQL lookups, and up to 8 × 20 × 10 = 1,600 identifier candidates before fusion. Projection reads add further round-trips.
- The application does not set a Hikari maximum pool size. With Hikari's default maximum of 10 connections, 10 concurrent document operations holding session advisory-lock connections leave no free connection for nested JDBC work. A Poisson/lognormal stress model with 1 s mean lock hold is highly sensitive once offered concurrency approaches that boundary; this is used only to prioritize D28, while the defect itself follows from the connection-allocation invariant.
- The reranker model from Audit 3 is also load-sensitive: with one worker and a 20-entry queue, cancellation of queued `FutureTask` instances does not create a transport-level guarantee that the underlying embed call stopped; D24 remains a separate poisoned-worker concern.



## Audit 5/10 findings

| ID | Sev | Area | Defect | Required remediation | Verification | Status |
| --- | --- | --- | --- | --- | --- | --- |
| D31 | P0 | lifecycle/publication architecture | The lifecycle model has only one `generation` plus one `lifecycle_status`. `beginIngestion(N+1)` immediately increments `generation` and changes the document to `INGESTING`, so the system no longer has an authoritative representation that generation N is still the published READY generation while N+1 is staging. This makes the D02/D03/D20 target semantics impossible to express cleanly with the current state model | separate published/active generation from working/staging generation, or model generations as separate rows with an atomic published pointer; retrieval and retention must consume the published pointer while ingestion mutates only staging state | state-machine integration test: N remains explicitly published/searchable while N+1 is INGESTING/FAILED, then one atomic publication switch makes N+1 active | OPEN |
| D32 | P1 | embedding lifecycle | Persisted vector state has no embedding provider/model/version/dimension/config fingerprint. Startup validation only checks configured pgvector dimensions/HNSW limits and does not prove the active embedding model matches persisted vectors. Changing the embedding model can silently query old vectors with a different embedding space (or fail later on dimension mismatch) with no mixed-profile detection or controlled re-embedding path | introduce a persisted EmbeddingProfile and associate every vector generation with it; validate active model/profile before serving, detect obsolete/mixed profiles, and implement generation-safe bounded re-embedding | same-dimension model/profile change is detected before mixed-space retrieval; dimension mismatch fails before writes; re-embedding crash preserves the prior published generation | OPEN |
| D33 | P0 | database migration safety | Liquibase changeset `001-canonical-retrieval-schema.sql` executes `DROP TABLE IF EXISTS document_identifier CASCADE` before recreating the identifier table. Any upgrade from a deployment where `document_identifier` already contains data can destroy identifier state. The repository also contains a conflicting standalone `identifier-schema.sql`, so upgrade behavior depends on which historical schema source was used | replace destructive bootstrap logic with an additive/data-preserving migration path; establish Liquibase as the single schema source of truth; add explicit legacy-to-current migration if historical installations are supported | Testcontainers upgrade test seeds the legacy identifier schema/data, runs Liquibase, and proves all rows/constraints required by the repository survive | OPEN |
| D34 | P2 | identifier capability contract | `IdentifierType` and README advertise CLAIM_NUMBER, PAYMENT_NUMBER, PROTOCOL_NUMBER, LETTER_NUMBER and DOCUMENT_ID, but production parser beans only exist for CONTRACT_NUMBER, ORDER_NUMBER, INVOICE_NUMBER, APPLICATION_NUMBER, CASE_NUMBER and DOCUMENT_NUMBER. The shared WRITE/READ extractor therefore cannot actually ingest or recognize several advertised identifier types | either implement parsers and normalization contracts for every advertised type or remove unsupported types from the capability contract/docs/API until implemented | parameterized ingestion/query tests for every supported IdentifierType, with startup/capability test proving no advertised type lacks a parser | OPEN |
| D35 | P1 | identifier identity | `IdentifierNormalizer` removes all punctuation, so distinct identifiers such as `AB-12` and `AB/12` collapse to the same normalized key `AB12`. Exact lookup can therefore return the wrong document, and within one document/chunk the unique constraint can overwrite one occurrence with the other | define type-aware canonicalization that preserves semantically significant separators or adds an unambiguous canonical encoding; do not use one lossy normalization rule for all identifier types | collision corpus proves semantically distinct raw identifiers never share an exact-match identity unless the type contract explicitly declares them equivalent | OPEN |
| D36 | P2 | configuration/chunking | `ChunkingProperties` has no validation. Invalid values and invalid ordering (`min > target`, `target > soft`, `soft > hard`, non-positive limits) are accepted at startup and can silently degrade chunking into one-character/one-unit fragmentation or violate configured hard/soft semantics | add validated configuration invariants for positive values and `min <= target <= soft <= hard`, with explicit safe upper bounds | application-context/property-binding tests reject invalid combinations at startup and accept documented defaults/overrides | OPEN |
| D37 | P2 | API/ingestion idempotency | `POST /api/knowledge/text` has no idempotency/version precondition semantics. If a client loses a successful response and retries the same request, the retry is treated as a new ingestion generation, redoes embeddings/index writes and refreshes lifecycle/TTL state even though the logical command is identical | define idempotency semantics (idempotency key and/or content/version fingerprint with conditional generation advance), persist request identity/result, and make retries return the original outcome without republishing equivalent state | lost-response retry test proves the same logical request does not create a new generation or refresh TTL; changed content/version still creates a new generation | OPEN |
| D38 | P2 | retention observability | The lifecycle specification requires structured retention logs and metrics for claimed/deleted/failed/stale/lease-lost work, backlog and run duration, but `RetentionScheduler`, `RetentionWorkerPool` and `ChunkRetentionService` emit none. Failures can persist only as row state without the operational signals required to detect a stuck backlog or repeated lease loss | add bounded-cardinality Micrometer metrics and structured lifecycle events for run, claim, delete, failure, stale claim, lease loss, recovery and backlog; never label with document text | meter/log tests distinguish successful deletion, retryable failure, stale claim and lease loss; backlog gauge changes with seeded lifecycle rows | OPEN |



## Audit 6/10 findings

| ID | Sev | Area | Defect | Required remediation | Verification | Status |
| --- | --- | --- | --- | --- | --- | --- |
| D39 | P1 | ingestion/runtime | The ingestion write path has no deadline around `vectorStore.add()` / embedding generation. A wedged vector/embedding dependency can hold the document advisory lock and leave lifecycle state `INGESTING` indefinitely; stale-ingestion recovery cannot acquire the same document lock to recover it while the blocked process/session remains alive | add bounded ingestion/vector-write deadlines at the transport and application levels, cancellation/isolation, and a deterministic fail/compensation path that releases the document operation lock | fault-injected vector/embedding call that never returns must cause ingestion to fail within the configured deadline, release the lock and leave a recoverable staged generation | OPEN |
| D40 | P1 | retention/queue leasing | Retention leases start when rows are claimed, but heartbeats start only when a queued claim actually begins `runClaim`. A claim can therefore expire while waiting in the worker queue. Reclaiming expired `DELETE_PENDING/DELETING` rows increments `attempt_count`; repeated queue expiry/reclaim can consume the retry budget without any cleanup attempt and eventually leave an expired deletion row permanently unclaimable because `attempt_count >= retryLimit` | do not start the operational lease before execution capacity exists, or heartbeat queued ownership; separate crash-reclaim count from cleanup-failure retry budget; guarantee an expired queued claim cannot exhaust deletion retries without running cleanup | deterministic queue/clock test with worker saturation and short leases proves queued claims neither expire silently nor consume cleanup retry budget before execution | OPEN |
| D41 | P2 | chunking/legal semantics | `DomainSemanticClassifier` uses substring matching and checks positive deontic terms before negative phrases. English `must not`/ `shall not` can be classified as OBLIGATION instead of PROHIBITION, `may not` as RIGHT, and short substring terms such as `must`, `may`, `days` can match unrelated words. This corrupts semantic-unit typing used by chunk protection decisions | use boundary-aware phrase/token matching, prioritize longer/negative deontic constructions before positive terms, and define multilingual polarity fixtures | legal classifier corpus covers must/shall/may and their negated forms plus substring counterexamples across supported languages | OPEN |
| D42 | P2 | chunking/Kazakh hierarchy | `legalLevel("ТАРМАҚША")` matches the earlier `startsWith("ТАРМАҚ")` branch and returns paragraph level 6 instead of subparagraph level 7. Kazakh `ТАРМАҚ` and `ТАРМАҚША` therefore collapse to the same hierarchy depth, causing the parent paragraph to be popped from the section stack | use exact canonical heading-token mapping (or test subparagraph before paragraph) rather than prefix matching for overlapping Kazakh terms | hierarchy fixture asserts `... > 1-тармақ > 1.1-тармақша` is preserved exactly for both numbered and non-numbered Kazakh headings | OPEN |
| D43 | P1 | executor/shutdown | The shared bounded executor uses `ThreadPoolExecutor.CallerRunsPolicy`. After an executor is shut down, that policy silently discards rejected tasks instead of throwing. Tasks submitted through `CompletableFuture.supplyAsync/thenApplyAsync` can therefore remain permanently incomplete, making ingestion/retrieval `join()` calls hang during shutdown races | use a rejection policy that completes asynchronous work exceptionally, coordinate graceful shutdown with request draining, and bound all joins/dependent stages with cancellation | shutdown-race tests prove tasks submitted after/while shutdown fail promptly and no ingestion/retrieval future remains incomplete | OPEN |



## Audit 7/10 findings

| ID | Sev | Area | Defect | Required remediation | Verification | Status |
| --- | --- | --- | --- | --- | --- | --- |
| D44 | P1 | Unicode/identity | Text, document identity and business identifiers have no explicit Unicode normalization boundary. Canonically equivalent strings (for example NFC `é` versus NFD `e + combining acute`) can produce different chunk SHA identities and different exact identifier keys; compatibility forms such as full-width Latin/digits also remain distinct unless explicitly normalized | define Unicode normalization policy at ingestion/query boundaries: NFC for canonical text/identity and a separately reviewed type-aware policy (optionally NFKC where semantically safe) for identifiers; apply the same normalization on WRITE and READ | corpus tests prove canonically equivalent Unicode forms resolve to the same logical identity while intentionally distinct compatibility/confusable characters follow the documented policy | OPEN |
| D45 | P1 | lifecycle/data volume | Stale-ingestion recovery reads only one `batchSize` of oldest `INGESTING` rows per scheduler run and then performs non-blocking advisory-lock attempts. Locked rows are skipped but not replaced with later candidates. Multiple pods can select the same oldest batch and all waste capacity on the same contended rows, starving later stale ingestions indefinitely while `maxBatchesPerRun` applies only to retention deletion | make stale-ingestion recovery itself claim/page work with bounded `SKIP LOCKED`/fencing semantics or continue scanning until recovery capacity is filled; multi-pod workers must receive disjoint recoverable candidates | Testcontainers test with more than one batch plus permanently locked oldest rows proves later stale rows are still recovered in the same run and pods do not repeatedly select the same batch | OPEN |
| D46 | P2 | malformed corpus/identifiers | Business identifier regexes capture unbounded values (`{2,}`), while `document_identifier.raw_value` and `normalized_value` are `VARCHAR(500)`. A document containing a syntactically matching contract/order/etc. token longer than 500 characters reaches persistence and fails the SQL write, turning one malformed identifier into whole-document ingestion failure | impose parser-level/canonical identifier length limits aligned with schema, reject or truncate only according to an explicit identifier contract, and expose malformed-identifier diagnostics without aborting unrelated content unless policy requires it | adversarial corpus with >500-character identifier tokens never reaches an oversized DB bind and has deterministic reject/skip behavior | OPEN |
| D47 | P1 | RAG/context envelope security | `source`, `sectionPath`, `documentId` and other provenance fields are serialized into the model context without escaping or structural encoding. Because source/title/metadata are ingestion-controlled strings, embedded newlines or `[SOURCE n]` markers can forge source boundaries/instructions even if chunk text itself is later hardened, undermining citation/source separation | serialize context as an explicit structured/escaped envelope, validate provenance fields, and ensure untrusted metadata cannot emit control delimiters or synthetic source headers | malicious source/title metadata containing newlines, fake `[SOURCE n]` markers and instruction text remains data inside one source record and cannot alter citation numbering or prompt structure | OPEN |
| D48 | P2 | Unicode/chunking | `OversizedUnitSplitter` chooses split offsets using UTF-16 `String.length()/substring` code-unit indexes and can fall back to an arbitrary `candidateEnd`. That boundary can land between a high/low surrogate, producing chunks with unpaired surrogates and corrupting supplementary Unicode characters before persistence/embedding | split only on Unicode code-point/grapheme-safe boundaries and make token/character budgeting explicit about code points versus UTF-16 units | oversized corpus beginning with an odd number of BMP code units followed by supplementary characters (emoji/CJK extension) never yields an unpaired surrogate and round-trips exactly after splitting/rejoin | OPEN |
| D49 | P1 | distributed leases/PostgreSQL | Retention lease ownership and stale-ingestion timing use JVM-provided `Instant` values from each pod (`claimExpired`, `renewLease`, `isCurrentClaim`, scheduler recovery) rather than one authoritative database clock. Clock skew across pods can cause premature lease steal, delayed recovery, or inconsistent fencing decisions even though ownership is persisted in PostgreSQL | move lease/staleness comparisons and lease extension timestamps to PostgreSQL `clock_timestamp()/now()` semantics (or another single authoritative time source); application clocks may remain for observability/tests but not ownership correctness | multi-client integration test injects opposing application clock skew and proves claim/renew/reclaim behavior is unchanged because DB time decides ownership | OPEN |
| D50 | P0 | chunk identity/data corruption | `ChunkIdentity` builds its SHA input by joining raw `documentId`, numeric `chunkIndex`, `sectionPath` and text with plain newline delimiters and no escaping/length-prefixing. Because document IDs/titles/section paths are not forbidden from containing newlines, different tuples can serialize to the exact same canonical byte string and therefore the exact same SHA-256. `knowledge_search_projection` uses `chunk_id` as a global PK and `ON CONFLICT (chunk_id) DO UPDATE` even rewrites `document_id`, so this deterministic serialization collision can silently reassign/overwrite another document's projection | replace delimiter concatenation with an unambiguous canonical encoding (length-prefixed/binary/structured serialization) and reject control characters in identifiers where appropriate; conflict updates must also enforce immutable ownership invariants | unit test proves formerly colliding tuples produce different IDs; PostgreSQL integration test proves a chunk ID can never migrate from one document owner to another on conflict | OPEN |



## Audit 8/10 findings

| ID | Sev | Area | Defect | Required remediation | Verification | Status |
| --- | --- | --- | --- | --- | --- | --- |
| D51 | P1 | identifier semantics | Business identifier regexes allow the marker (`№/no/number`) and separator to be absent while accepting letters-only values. Ordinary prose such as `contract termination`, `order status` or `case management` is therefore indexed/detected as CONTRACT_NUMBER=`termination`, ORDER_NUMBER=`status`, CASE_NUMBER=`management`. This poisons both ingestion identifier state and query planning; after D25 fail-closed scoping, such false positives can suppress otherwise valid semantic retrieval | require an unambiguous identifier signal (explicit marker, punctuation/shape policy, or type-specific validated value grammar); add negative-language corpora and confidence/capability semantics rather than treating every regex match as authoritative exact identity | ingestion and query tests prove ordinary domain prose after contract/order/case/document words produces no identifier, while real marked/shape-valid identifiers still resolve | OPEN |
| D52 | P1 | retrieval authority | Exact identifier/reference evidence has no authority policy after retrieval. `ResultFusion` treats all channels as ordinary RRF evidence and `Reranker` semantically reranks every candidate regardless of evidence type, so an exact identifier target can be placed below a semantic-only candidate. This contradicts the architecture requirement that exact-match authority not be destroyed without an explicit policy | encode evidence authority in fusion/reranking policy; exact identifier/reference matches must be pinned, separately tiered, or explicitly policy-weighted before semantic reranking | mixed exact-identifier + semantic-noise test proves exact authoritative evidence cannot be demoted below non-authoritative candidates unless a configured policy explicitly allows it | OPEN |
| D53 | P2 | retrieval expansion | Neighbor expansion runs after reranking but appends expanded neighbors after the complete ranked list. `ContextBudget` then consumes hits in order up to `contextMaxChunks`. When ranked candidates already fill the context (common when rerankerCandidates/vector+lexical exceed the 12-chunk default), all expansion work is discarded and cannot affect the answer | interleave/score expansion relative to its seed, reserve an explicit expansion budget, or perform expansion before final context selection with provenance-aware ranking | test with >contextMaxChunks ranked candidates proves a configured high-priority seed neighbor can enter final context and does not disappear solely because expansion was appended after all originals | OPEN |
| D54 | P1 | chunking/domain semantics | `StructuralUnitExtractor` applies the generic numbered-heading pattern to every domain before semantic classification. Medical list items such as `1. Take 10 mg once daily.` become HEADING, and `SemanticChunker` deliberately skips `DomainSemanticClassifier` for headings. Numbered DOSAGE/CONTRAINDICATION/MONITORING facts can therefore lose their medical type and atomicity, especially when several short numbered items are grouped | make structural parsing domain-aware; generic numbered headings must not override medical semantic facts without a stronger structural signal, and domain classification must be able to refine structural units | numbered medical-list fixtures across RU/KK/EN/ZH preserve each dosage/contraindication/monitoring item as the correct atomic semantic type | OPEN |
| D55 | P2 | query decomposition | `QueryDecomposer` silently truncates retrieval intent after `MAX_SEGMENTS=8`. For coordinated clauses the original compound clause itself consumes one slot, so the final independent intent can be dropped even sooner. Because multi-sentence input does not retain the full original as a retrieval unit, trailing intents receive no retrieval path and the caller gets no degradation signal | define explicit overflow semantics: preserve a catch-all original query, summarize/merge overflow, or return diagnostics; bound execution without silently losing user intents | 9+ intent and 8-way coordinated-query tests prove every intent remains represented by a retrieval unit or an explicit overflow/catch-all contract | OPEN |
| D56 | P1 | chunking/boundedness | The configured `hardMaxTokens` is only enforced per `SemanticUnit`, not per final grouped chunk. In `SemanticChunker.group`, if the current group is below `minTokens`, a following unit can push the group beyond soft and hard limits because the pre-split condition is gated by `enoughContent`; the oversized group is then emitted after crossing the target | enforce hard max as an unconditional final-chunk invariant independent of minimum/target preferences; min/soft constraints may guide packing but can never override hard max | constructed sequence such as a sub-minimum first unit plus a near-hard-max second unit never emits a chunk whose estimated size exceeds hardMaxTokens | OPEN |
| D57 | P2 | embedding payload budget | Chunk sizing is performed on unit/raw chunk text, but the actual embedding payload is later expanded by `EmbeddingTextBuilder` with document title, domain, language and full section path. Therefore even a raw chunk exactly within the configured hard limit can produce an embedding request above that limit; the character/3.2 estimator also does not represent the actual active model tokenizer | budget the exact serialized embedding payload with a tokenizer/profile-aware estimator, or reserve deterministic envelope overhead tied to the persisted embedding profile; distinguish raw-text and embedding-payload limits | long-title/deep-section fixture proves final `embeddingText` remains within the model/profile token limit, not merely raw chunk text | OPEN |
| D58 | P1 | verification architecture | Critical vector consistency contracts are not exercised through the production Spring AI PgVectorStore adapter. Repository Testcontainers tests use PostgreSQL for relational projections/lifecycle while ingestion/retention tests mock `VectorStore`; the test tree contains no PgVectorStore/live vector-store E2E. Consequently physical ID behavior, metadata filters, real add/delete semantics and D01/D19 compensation assumptions cannot reach VERIFIED status from the current CI evidence alone | add a production-adapter E2E gate using PostgreSQL+pgvector and a deterministic embedding model/stub at the Spring AI adapter boundary; test real add/search/filter/delete, generation visibility and partial/failure compensation semantics | exact-SHA CI includes an E2E that instantiates the production PgVectorStore configuration and proves write/search/filter/delete/generation contracts without mocking VectorStore | OPEN |



## Audit 9/10 findings

| ID | Sev | Area | Defect | Required remediation | Verification | Status |
| --- | --- | --- | --- | --- | --- | --- |
| D59 | P1 | PostgreSQL lexical/data volume | KK/ZH trigram retrieval includes `lower(text_content) LIKE '%' || lower(?) || '%'` without escaping SQL LIKE metacharacters. Prepared binding prevents SQL injection but `%` and `_` inside the user query still act as wildcards. A query such as `%` can make the LIKE branch match the entire language corpus and force ranking over all matching rows; ordinary text such as `5%` also has non-literal semantics | escape `%`, `_` and the escape character and use an explicit `ESCAPE` clause; define a minimum/selectivity policy for very short trigram queries and keep the literal query semantics identical across languages | Testcontainers tests for `%`, `_`, `5%` and literal wildcard-containing KK/ZH queries prove only literal matches are returned; large-corpus EXPLAIN gate prevents wildcard-driven full-corpus ranking | OPEN |
| D60 | P1 | retention/heartbeat concurrency | All active retention claims share one `ScheduledThreadPoolExecutor(1)` for heartbeat renewal, and each heartbeat performs synchronous JDBC work with no per-renewal timeout. One blocked/slow `renewLease` call can head-of-line block heartbeat execution for every other claim until their leases expire, causing unrelated workers to lose ownership and become reclaimable | remove the single heartbeat failure domain: batch/transactionally renew claims or use bounded isolated heartbeat concurrency, add DB/query deadlines and sufficient lease safety margin, and preserve per-claim fencing | deterministic test blocks renewal for claim A while claim B is active and proves B continues to renew before expiry; database-stall fault injection cannot expire all unrelated leases | OPEN |
| D61 | P1 | multilingual/chunking | Document sentence segmentation requires whitespace after `。！？`. Normal Chinese prose typically has no whitespace between sentences, so text such as `剂量...。禁忌...。监测...。` remains one semantic unit. Medical classification then assigns one type to the combined block (for example CONTRAINDICATION wins because it is checked first) and atomic protection preserves the mixed facts together, violating the Chinese medical atomic-fact contract | implement language/script-aware sentence segmentation that splits Chinese punctuation without requiring whitespace, then classify/protect each sentence/fact independently | single-paragraph no-whitespace Chinese fixtures containing dosage + contraindication + monitoring produce separate correctly typed atomic units/chunks and round-trip all text | OPEN |
| D62 | P1 | retrieval/failure semantics | `ParallelRetrievalExecutor` converts exceptional strategy futures to `List.of()`, so downstream code cannot distinguish a genuine zero-hit result from a backend failure. If all critical retrieval paths fail, `RagQuestionService` returns the normal “insufficient information” answer; identifier-strategy failure can also collapse to the same empty dependency state that currently triggers D25 global fallback | carry typed retrieval outcomes (hits, empty, failed, timed out, rejected) through plan execution; define per-strategy and aggregate degraded/fail-closed policy; never represent infrastructure failure as a normal zero-hit result | healthy zero-hit query returns the normal insufficient-information response, while all-strategy failure and exact-identifier backend failure produce an explicit degraded/error outcome and cannot trigger unrestricted semantic fallback | OPEN |
| D63 | P2 | configuration/reranker | `RetrievalProperties.rerankerTimeout` is only `@NotNull`. Zero, negative, sub-millisecond and arbitrarily large durations pass startup validation. The implementation converts the duration with `toMillis()`; sub-millisecond positive values become 0 and behave as immediate timeout, while very large values defeat the intended bounded reranker contract | validate a positive operational range for reranker timeout and avoid lossy duration conversion for supported precision; reject impossible/unbounded timeout configurations at startup | configuration-binding tests reject zero/negative/too-small/too-large durations and prove the minimum accepted value produces a non-zero effective timeout | OPEN |
| D64 | P2 | multilingual/references | `CrossReferenceExtractor` does not recognize common target-language legal-reference forms: Kazakh number-before-label forms such as `25-бап` / `1-тармақ`, or Chinese forms such as `第25条` / `第二十五条`. These references are silently absent from canonical chunks even after D27 adds a real reference index/producer | define canonical typed cross-reference parsing for KK/RU/EN/ZH, including language-native number/order forms, and normalize declarations versus references consistently | golden corpus extracts equivalent article/paragraph references from KK/RU/EN/ZH forms into the same typed canonical identity | OPEN |
| D65 | P2 | readiness/operations | The application includes Actuator but defines no application readiness contract for the dependencies that make ingestion/RAG usable: Ollama chat, Ollama embeddings, and vector-store/model compatibility. With PostgreSQL healthy, process/DB health can remain green while both ingestion and final answer generation are unusable because Ollama/model state is unavailable or incompatible | add readiness contributors for required AI/model/vector capabilities, separate liveness from readiness, and include embedding-profile/dimension compatibility without performing expensive inference on every probe | application-context/integration test with healthy PostgreSQL but unavailable Ollama reports NOT_READY; compatible dependencies report READY; liveness remains independent of transient AI dependency failure | OPEN |



## Audit 10/10 findings

| ID | Sev | Area | Defect | Required remediation | Verification | Status |
| --- | --- | --- | --- | --- | --- | --- |
| D66 | P1 | retention/lease fencing | `markFailed` validates document/generation/claim token but does not require the lease to still be valid. A worker whose lease has already expired (but has not yet been reclaimed) can catch a cleanup error, transition the row to `DELETE_FAILED`, increment `attempt_count` and clear ownership as though it were still current. This can consume retry budget and overwrite the lifecycle state after ownership has logically expired | make every state-mutating completion/failure transition lease-fenced using the authoritative DB clock; an expired claim may report local failure but must not mutate lifecycle state | Testcontainers race expires a claim immediately before cleanup throws and proves the stale worker cannot call an effective `markFailed`/increment attempts; a current claim still can | OPEN |
| D67 | P1 | reranker/model-response integrity | `EmbeddingSemanticRerankScorer` accepts non-finite vector components/scores and silently maps dimension mismatch to score 0. A NaN component produces a NaN cosine/rerank score; Java's reversed double comparator orders NaN ahead of finite scores, so a malformed embedding response can promote the corrupt candidate to rank 1 instead of triggering fallback | validate embedding batch shape, dimensions and every component/derived score as finite before ranking; malformed model output must fail the whole rerank attempt and fall back to original retrieval order | scorer/reranker tests with NaN, +Infinity, -Infinity and inconsistent dimensions prove no malformed score reaches sorting and original RRF order is preserved | OPEN |
| D68 | P1 | persistence/transactionality | Canonical projections, identifiers and vector-generation manifests use `JdbcTemplate.batchUpdate(..., batchSize=100)` but `PersistenceCoordinator.persist` has no transaction spanning those relational writes. A failure in a later sub-batch can leave earlier sub-batches committed while lifecycle becomes `INGEST_FAILED`, creating deterministic partial relational state for documents with >100 rows | generation-scope the staged rows and make each relational staging phase atomic (transaction) or provide exact attempted-row compensation/reconciliation; never expose/retain an untracked successful prefix of a failed batch | Testcontainers fault injection with >100 projections/identifiers/manifests fails in the second sub-batch and proves zero staged rows remain visible/owned after failure (or the complete staged generation is durably reconcilable) | OPEN |
| D69 | P2 | quality-metric oracle | Test `RetrievalQualityMetrics.ndcgAtK` counts duplicate appearances of the same relevant chunk repeatedly. For relevant `{A}` and ranking `[A, A]`, the implementation returns about 1.6309 even though normalized DCG must be in [0,1]. Duplicate-producing regressions can therefore inflate the quality gate rather than fail it | deduplicate document/chunk identity for metric gain (or explicitly define graded judgments) and assert metric range invariants; add property-based tests for duplicates/permutations | property tests guarantee 0 <= nDCG <= 1 and adding a duplicate of an already retrieved relevant chunk cannot increase gain beyond the ideal ranking | OPEN |
| D70 | P1 | verification architecture | `MultilingualRetrievalQualityRegressionTest` compares hard-coded `baselineRanked` and hard-coded `finalRanked` lists; no production retrieval component participates. The test remains green if planner/vector/fusion/reranker production code is catastrophically broken. Documentation acknowledges the fixture is not pipeline-backed, while CI contains only normal `mvn clean verify` and no live/full retrieval quality job | make the sign-off quality gate execute the production retrieval pipeline over a versioned corpus with deterministic embeddings/adapters where possible, plus a separate live-model gate; fixtures may test metric arithmetic but must not serve as production non-regression evidence | mutation test deliberately breaks production ranking/fusion and proves the quality gate fails; exact-SHA CI publishes per-language Recall/MRR/nDCG from actual pipeline output | OPEN |
| D71 | P1 | retention/vector identity | When a generation manifest is missing, retention/replacement infers legacy physical vector IDs from `projection.chunk_id`. Current runtime vectors use name-based UUID physical IDs, while `VectorIdentity` defines yet another `document::gN::chunk` form. Missing-manifest state therefore contains no proof that chunk IDs are the correct physical identity; cleanup can delete zero real vectors, delete relational state, and still mark the document `DELETED`, leaving retained vector data behind | persist/vector-version the physical identity scheme; treat a missing manifest as an explicit reconciliation state, not proof of a legacy scheme; enumerate/delete vectors through a verifiable generation/document predicate or audited migration mapping before marking DELETED | integration test seeds a missing-manifest generation using current physical IDs and proves retention cannot mark DELETED until every physical vector is verified absent; legacy migration fixture separately proves supported historical IDs | OPEN |
| D72 | P2 | query parsing | Query decomposition treats any period followed by whitespace as a sentence boundary. Common legal/technical abbreviations such as `ст. 25`, `п. 3`, `Art. 25`, `No. 42` are split into separate retrieval units, severing the abbreviation from its number/context before identifier/reference/semantic planning | use abbreviation-aware sentence segmentation (or a locale-aware sentence boundary iterator with domain exceptions) and preserve abbreviation+number tokens as one retrieval unit | RU/EN legal query corpus proves `ст. 25`, `п. 3`, `Art. 25`, `No. 42` stay attached while genuine adjacent sentences still split | OPEN |


## Remediation order

### Wave 1 — consistency and visibility

```text
D33 migration safety / schema source of truth
D31 active-vs-staging generation state model
D20 generation-scoped lexical/identifier publication
D68 atomic relational staging batches
D03 non-destructive replacement
D01 orphan vectors
D39 bounded ingestion/vector-write deadline
D02 published-generation visibility
D50 unambiguous chunk identity serialization
D19 vector identity canonicalization
D44 Unicode canonicalization boundary
D32 embedding profile / re-embedding lifecycle
```

### Wave 2 — retrieval correctness

```text
D04 canonical representative
D05 identifier-only canonical resolution
D06 RU/EN FTS indexes
D59 literal/selective KK/ZH trigram queries
D07 retrieval deadlines
D62 typed retrieval failure outcomes
D08 saturation isolation
D43 executor shutdown/rejection semantics
D51 identifier false-positive grammar
D52 exact-match authority preservation
D25 identifier-scope fail-closed semantics
D35 collision-safe identifier identity
D34 complete identifier capability contract
D27 real cross-reference indexing contract
D26 reference fan-out/query amplification
D53 expansion/context integration
D55 query-decomposition overflow semantics
D72 abbreviation-safe query segmentation
D24 reranker poisoned-worker isolation
D67 finite/shape-valid reranker model responses
D63 validated reranker timeout semantics
D29 answer-generation deadline
D23 exact assembled-context budget
```

### Wave 3 — lifecycle/concurrency

```text
D09 retention shutdown race
D40 queued-claim lease/retry semantics
D60 heartbeat isolation / renewal deadlines
D49 database-authoritative lease clock
D66 failure-transition lease fencing
D45 stale-ingestion recovery fairness/paging
D22 retention lease fencing
D28 JDBC/advisory-lock pool starvation
D71 manifest/physical-vector identity reconciliation
D38 retention observability
```

### Wave 4 — trust, provenance and API boundaries

```text
D10 prompt injection boundary
D47 structured/escaped context envelope
D11 citation requirement
D12 citation sanitization
D21 citation numeric overflow
D13 provenance contract
D14 expansion provenance
D15 language canonicalization
D16 ambiguous language routing
D41 legal semantic polarity/token boundaries
D42 Kazakh paragraph/subparagraph hierarchy
D54 numbered medical-list semantic preservation
D61 Chinese no-whitespace sentence atomicity
D64 multilingual cross-reference grammar
D56 final chunk hard-max invariant
D57 exact embedding-payload budget
D48 Unicode-safe oversized splitting
D46 bounded derived identifier length
D17 request limits
D36 chunking configuration invariants
D37 ingestion idempotency semantics
D30 metadata canonicalization
D65 AI/vector readiness contract
D18 authentication/authorization
```

## Required final gate

```text
all P0/P1 = VERIFIED
all accepted P2 have explicit disposition
D69 bounded/correct retrieval-quality metrics
D70 production-pipeline quality regression gate
D58 production PgVectorStore E2E gate
full Testcontainers suite
spotless
mvn clean verify
exact-SHA GitHub Actions success
Audit 10/10 completed
```
