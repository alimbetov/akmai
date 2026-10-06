# RAG Assurance RA-02…RA-08 — Detailed Technical Specification

**Status:** Proposed  
**Branch:** `feature/rag-assurance-ra02-ra08`  
**Base:** `main` after RA-01 merge (`de89ad2678fb2bf72177324c261df2e991f627c2`)  
**Depends on:** RA-01 assertion foundation already merged into `main`  
**Scope:** complete the correctness/safety contract suite for AkmAI WRITE and READ RAG pipelines  
**Out of scope:** retrieval-quality optimization, Recall/MRR/nDCG gates, performance tuning, latency baselines, live-model evaluation

---

## 1. Objective

RA-01 created the reusable assertion foundation. RA-02 through RA-08 must turn that foundation into an executable RAG correctness specification covering the complete production lifecycle:

```text
WRITE correctness
    +
READ correctness
    +
multilingual/domain fixtures
    +
security isolation
    +
mutation smoke
    +
CI enforcement
        |
        v
AkmAI RAG correctness is continuously executable and regression-protected
```

The target is not additional unit-test volume. The target is a **contract suite** that proves externally observable guarantees and fails whenever a critical guarantee is intentionally broken.

The suite MUST answer these questions:

1. Does the WRITE pipeline persist only valid, deterministic, attributable evidence?
2. Does the READ pipeline retrieve, combine, filter, budget and validate evidence safely?
3. Do legal and medical guarantees hold in `kk`, `ru`, and `en`?
4. Can inaccessible, unpublished, expired, superseded or incompatible evidence leak into final answer context?
5. Do the contracts detect intentionally broken implementations?
6. Does a clean checkout execute the correctness suite with `mvn clean verify` without a live Ollama dependency?

---

## 2. Current baseline after RA-01

The following foundation already exists and MUST be reused rather than duplicated:

```text
src/test/java/kz/alimbetov/akmai/rag/assurance/
    RagAssertions.java
    RagAssertionsFoundationTest.java

    assertion/
        AssuranceFailure.java
        ChunkAssertions.java
        RetrievalAssertions.java
        ContextAssertions.java
        CitationAssertions.java
        GroundingAssertions.java
        LifecycleAssertions.java
        SecurityAssertions.java
```

Current reusable capabilities include primitives for:

- chunk non-emptiness;
- document identity;
- chunk ID uniqueness/determinism;
- hard token limits;
- duplicate canonical-hit detection;
- retrieval evidence/provenance presence;
- routing identity;
- context token budgets;
- invalid citation source numbers;
- grounded / grounded-or-abstains behavior;
- generation identity;
- published-generation predicates;
- ACL leakage.

RA-02…RA-08 MUST extend these typed assertions only where a reusable guarantee is missing. They MUST NOT replace the facade with ad-hoc assertions copied into individual scenario tests.

---

## 3. Production seams the contracts must protect

The contract suite is anchored to public behavior around existing AkmAI components.

### 3.1 WRITE pipeline

```text
document
 -> TextNormalizer
 -> structural extraction / semantic classification
 -> atomic-unit protection
 -> SemanticChunker / hierarchical chunking
 -> KnowledgeChunk
 -> enrichment
 -> identifiers / references
 -> embedding text
 -> embedding/profile selection
 -> generation-scoped persistence
 -> publication
```

Relevant existing seams include:

- `TextNormalizer`;
- `StructuralUnitExtractor`;
- `AtomicUnitProtector`;
- `SemanticChunker`;
- `HierarchicalChunker` where the production path uses hierarchical chunking;
- `TokenEstimator`;
- `EmbeddingTextBuilder`;
- `KnowledgeChunk`;
- semantic chunk annotation / identifier/reference enrichment;
- generation-aware search projections and identifier/reference projections;
- vector publication/lifecycle state;
- embedding runtime profile state and re-embedding lifecycle.

### 3.2 READ pipeline

```text
question
 -> query chunking / analysis
 -> RetrievalPlanner / RetrievalPlan
 -> parallel retrieval lanes
 -> ResultFusion
 -> Reranker
 -> KnowledgeExpansion
 -> TemporalAuthorityFilter
 -> ContextBudget / ContextAssembler
 -> published revalidation
 -> answer generation boundary
 -> CitationValidator
 -> AnswerGroundingVerifier
 -> response
```

Relevant existing seams include:

- `RagQuestionService` as public orchestration boundary;
- `RetrievalPlanner` / `RetrievalPlan`;
- `ParallelRetrievalExecutor`;
- vector, lexical, identifier and reference retrieval strategies;
- `ResultFusion`;
- `Reranker`;
- `KnowledgeExpansion`;
- `TemporalAuthorityFilter`;
- `ContextBudget` / `ContextAssembler`;
- published projection readers / lifecycle resolution;
- `CitationValidator`;
- `AnswerGroundingVerifier`.

### 3.3 Contract rule

Tests MAY replace nondeterministic external collaborators with deterministic test doubles, but MUST exercise production public behavior around the guarantee being tested.

Tests MUST NOT:

- call private methods through reflection;
- copy production algorithms into test code;
- assert internal helper invocation counts unless the invocation itself is a documented contract;
- assert exact floating-point scores unless a score is explicitly part of a public contract;
- assert exact LLM prose;
- depend on a live Ollama server.

---

## 4. Global engineering rules

### 4.1 Contract over implementation

Each test MUST state a stable invariant using a contract ID (`W-01…W-10`, `R-01…R-12`) and verify observable output.

### 4.2 Fail closed

The following boundaries MUST fail closed:

- ACL/security;
- publication/generation lifecycle;
- temporal/authority validity;
- embedding-profile compatibility;
- citation validity;
- grounding.

### 4.3 Determinism

Core assurance tests MUST be deterministic:

- fixed fixture input;
- fixed `Clock` where time matters;
- deterministic fake/model collaborators;
- no live network dependency;
- no probabilistic ranking acceptance criteria.

### 4.4 Test layering

Use three layers:

**Layer A — pure contracts**

- fast;
- no Spring context when unnecessary;
- no PostgreSQL;
- no Ollama.

**Layer B — component/integration contracts**

Use Testcontainers/PostgreSQL only where persistence/lifecycle/projection/vector/profile behavior is part of the invariant.

**Layer C — cross-stage scenarios**

Exercise orchestration with deterministic collaborators and verify final observable guarantees.

### 4.5 Failure diagnostics

Every new reusable assertion SHOULD report:

```text
[RAG-CONTRACT <id>]
fixture=<fixture-id>
invariant=<human-readable invariant>
violating=<chunk/hit/source/document/profile identity>
details=<expected/actual reproduction data>
```

---

## 5. Target test/resource structure

```text
src/test/java/kz/alimbetov/akmai/rag/assurance/
    RagAssertions.java

    assertion/
        ChunkAssertions.java
        RetrievalAssertions.java
        ContextAssertions.java
        CitationAssertions.java
        GroundingAssertions.java
        LifecycleAssertions.java
        SecurityAssertions.java
        IdentifierAssertions.java          # add only if ownership rules do not fit ChunkAssertions
        ReferenceAssertions.java           # add only if ownership rules do not fit ChunkAssertions
        EmbeddingProfileAssertions.java    # add for W-10 if reusable

    support/
        RagContractFixture.java
        RagContractFixtureLoader.java
        RagContractScenario.java           # optional reusable scenario input
        RagContractHarness.java            # reusable observable contract runner, not production copy

    write/
        NormalizationContractTest.java
        ChunkingContractTest.java
        MetadataContractTest.java
        AtomicFactContractTest.java
        IdentifierContractTest.java
        ReferenceContractTest.java
        PublicationContractTest.java
        EmbeddingProfileContractTest.java

    read/
        QueryContractTest.java
        RetrievalContractTest.java
        FusionContractTest.java
        RerankingContractTest.java
        ExpansionContractTest.java
        ContextContractTest.java
        CitationContractTest.java
        GroundingContractTest.java

    scenarios/
        LegalPipelineContractTest.java
        MedicalPipelineContractTest.java
        MultilingualPipelineContractTest.java
        SecurityIsolationContractTest.java

    mutant/
        ReverseReranker.java
        AclBypassRetriever.java
        CitationAcceptAll.java
        BrokenFusion.java
        AtomicFactSplitter.java
        GenerationIgnoringReader.java
        MutationSmokeTest.java
```

Resources:

```text
src/test/resources/rag-contracts/
    schema/
        rag-contract-fixture-v1.example.yaml

    legal/
        kk/
        ru/
        en/

    medical/
        kk/
        ru/
        en/
```

Do not create `technical/` as a RA-05 completion requirement. Legal and medical across three languages are the mandatory matrix for this phase. Additional domains may be added later.

---

# 6. RA-02 — WRITE contracts

## 6.1 Goal

Implement W-01 through W-10 against the actual WRITE pipeline boundaries so persisted/published evidence is deterministic, bounded, attributable and lifecycle-safe.

## 6.2 RA-02.1 — W-01 Normalization determinism

### Invariant

For identical canonical document input and identical configuration, normalization MUST produce equivalent canonical text and stable normalization-derived metadata.

### Test design

Create `NormalizationContractTest`.

Required cases:

1. identical input executed repeatedly produces identical normalized text;
2. CRLF/LF, repeated whitespace and Unicode-safe normalization follow existing production semantics deterministically;
3. normalization does not silently change document identity or required classification metadata;
4. input mutation that is semantically relevant must be observable rather than accidentally collapsed if production semantics require distinction.

### Boundary

Prefer the public normalization/chunking entry boundary over private normalizer helpers when the metadata contract is only observable after chunk creation.

### Acceptance

- repeated execution is deterministic;
- no private-method coupling;
- failure identifies fixture and divergent property;
- no full-text snapshot unless canonical text itself is the contract.

---

## 6.3 RA-02.2 — W-02 Stable chunk identity

### Invariant

The same canonical input and same chunking configuration MUST yield stable canonical chunk identity.

### Required checks

- same number of chunks;
- same chunk IDs;
- same chunk order where order is part of the document structure contract;
- same document ownership;
- equivalent chunk boundaries/canonical payload;
- no duplicate IDs.

Use existing `ChunkAssertions.hasUniqueChunkIds()` / `hasChunkIds(...)` and extend only where boundary comparison is reusable.

### Required negative case

A deterministic test double that perturbs chunk identity without changing canonical input MUST be detected by the contract assertion/harness.

### Acceptance

A refactor of private chunking helpers must not fail the contract if canonical chunk boundaries and IDs remain equivalent.

---

## 6.4 RA-02.3 — W-03 Non-empty chunks

### Invariant

No chunk eligible for persistence/publication may contain blank canonical content or blank embedding content.

### Required checks

- `normalizedText` non-blank;
- `embeddingText` non-blank;
- blank/whitespace-only source does not produce a publishable empty chunk;
- enrichment cannot turn a valid chunk into blank embedding payload.

Use `ChunkAssertions.containsNoEmptyChunks()`.

### Acceptance

At least one edge fixture containing whitespace/empty structural units proves the WRITE path does not persist a blank chunk.

---

## 6.5 RA-02.4 — W-04 Hard token envelope

### Invariant

The **final embedding payload** after enrichment MUST fit the configured hard token envelope.

### Critical requirement

Do not test only the pre-enrichment chunk text. The assertion MUST inspect the payload actually used toward embedding/persistence.

### Required cases

1. chunk just below hard limit passes;
2. oversized structural unit is split/bounded;
3. metadata/enrichment increases embedding payload but final payload remains within limit;
4. protected atomic facts remain intact where possible while the hard limit remains absolute;
5. impossible atomic unit larger than hard limit follows the production fail/split policy deterministically and never silently emits an over-limit final payload.

Use production `TokenEstimator` and existing `ChunkAssertions.respectsHardTokenLimit(...)`.

### Acceptance

Every final chunk in the fixture set is proven `<= hardMaxTokens`.

---

## 6.6 RA-02.5 — W-05 Metadata preservation

### Invariant

Required identity, routing, filtering and attribution metadata MUST survive normalization, chunking and enrichment.

### Minimum required fields

Where represented by the current model/configuration, verify:

- `documentId`;
- `chunkId`;
- language;
- domain;
- access/routing classification required for READ-time isolation;
- structural title/section attribution where applicable;
- generation ownership at persistence boundary;
- metadata required by identifier/reference attribution.

### Test strategy

Do not assert every metadata key. Define an explicit required metadata contract per fixture.

### Acceptance

A contract failure identifies the missing/changed field and affected chunk.

---

## 6.7 RA-02.6 — W-06 Protected atomic facts

### Invariant

Protected facts MUST not be split in a way that destroys their meaning when a valid chunk boundary can preserve them.

### Mandatory fact families

**LEGAL**

```text
RULE + EXCEPTION
ARTICLE/CLAUSE + operative condition
```

**MEDICAL**

```text
DRUG + DOSE + ROUTE + FREQUENCY
```

### Mandatory examples

At minimum fixtures must include:

- legal rule with an exception that changes applicability;
- medical instruction containing drug name, numeric dose, route and frequency;
- boundary pressure near target/soft token limits.

### Assertion

Add a reusable `preservesAtomicFact(...)` capability to `ChunkAssertions` or a focused assertion class. It SHOULD accept required fact fragments/groups from the fixture rather than hard-code legal/medical vocabulary.

### Negative case

`AtomicFactSplitter` must separate a protected pair/group and be killed in RA-07.

### Acceptance

The relevant fact group is recoverable within the same canonical chunk/evidence unit according to fixture semantics.

---

## 6.8 RA-02.7 — W-07 Identifier authority

### Invariant

Canonical identifiers MUST retain exact value and correct ownership through enrichment and persistence.

### Required checks

- identifier value remains canonical/stable;
- identifier resolves to the correct document;
- where chunk ownership exists, identifier resolves to the correct chunk/generation;
- identifier is not silently reassigned after re-chunking/re-publication;
- failed staged generation cannot expose identifiers before publication;
- duplicate/ambiguous identifiers follow current production authority rules deterministically.

### Layers

- Layer A: enrichment/value-object ownership;
- Layer B: generation-aware identifier projection and published reader behavior.

### Acceptance

Identifier authority is verified before and after publication boundary.

---

## 6.9 RA-02.8 — W-08 Reference authority

### Invariant

References MUST retain the correct owning and target evidence identity through chunking, enrichment and publication.

### Required checks

- canonical reference survives enrichment;
- source ownership is stable;
- target resolution is stable;
- reference from staged/unpublished generation is not readable;
- re-publication does not silently attach an old reference to a new unrelated chunk;
- exact reference semantics are preserved for later READ authority tests.

### Layers

- Layer A: reference enrichment/ownership;
- Layer B: published reference projection behavior.

### Acceptance

A reference contract failure reports source document/chunk, reference value, expected target and actual target.

---

## 6.10 RA-02.9 — W-09 Publication isolation

### Invariant

Unpublished, failed, superseded or partially staged generation data MUST NOT become readable evidence.

### Required integration scenarios

Use PostgreSQL/Testcontainers and the existing generation/publication repositories.

#### Scenario A — failed N+1

```text
generation N = published
stage N+1
fail before publication switch
```

Expected:

- N remains readable;
- N+1 is invisible through normal published readers;
- vector/lexical/identifier/reference modalities remain consistent;
- no partial replacement of N by N+1.

#### Scenario B — successful N+1 publication

```text
generation N = published
stage N+1
publish N+1 atomically according to production lifecycle
```

Expected:

- N+1 becomes the readable generation;
- N no longer competes as current evidence;
- all supported retrieval projections resolve the same published generation.

### Acceptance

The contract proves publication behavior, not repository implementation details.

---

## 6.11 RA-02.10 — W-10 Embedding profile consistency

### Invariant

A retrieval/vector comparison MUST NOT mix incompatible embedding spaces/profiles as if their vectors were directly comparable.

### Required scenarios

1. normal active profile: all readable vectors belong to compatible profile;
2. migration/re-embedding state: active vs migration profile behavior follows current production runtime rules;
3. old profile rows cannot leak into a query evaluated against an incompatible active profile;
4. publication/re-embedding transitions preserve readable-generation consistency.

### Assertion

Add `EmbeddingProfileAssertions.hasSingleCompatibleProfile(...)` or equivalent reusable primitive using public profile identity, not vector internals.

### Acceptance

At least one negative fixture containing mixed profile identities is rejected.

---

## 6.12 RA-02 deliverables

Expected classes/resources:

```text
write/NormalizationContractTest.java
write/ChunkingContractTest.java
write/MetadataContractTest.java
write/AtomicFactContractTest.java
write/IdentifierContractTest.java
write/ReferenceContractTest.java
write/PublicationContractTest.java
write/EmbeddingProfileContractTest.java
```

### RA-02 Definition of Done

```text
[ ] W-01 deterministic normalization
[ ] W-02 stable chunk identity
[ ] W-03 no empty persisted/published chunks
[ ] W-04 final embedding payload respects hard token limit
[ ] W-05 required metadata preserved
[ ] W-06 protected legal/medical atomic facts preserved
[ ] W-07 identifier authority preserved
[ ] W-08 reference authority preserved
[ ] W-09 failed/unpublished generation invisible
[ ] W-10 incompatible embedding profiles not mixed
[ ] tests use public behavior, not private methods
[ ] Layer A tests require no PostgreSQL/Ollama
[ ] persistence/lifecycle tests use deterministic Testcontainers setup
```

---

# 7. RA-03 — READ retrieval/fusion contracts

## 7.1 Goal

Implement R-01 through R-07 for query intent, retrieval provenance, ACL/lifecycle isolation, fusion, authority, reranker fallback and expansion safety.

## 7.2 RA-03.1 — R-01 Query lane intent

### Invariant

Query planning MUST retain the retrieval capabilities required by the detected intent.

### Required cases

- exact identifier query includes an identifier-capable lane;
- exact reference query includes a reference-capable lane where supported;
- semantic question retains semantic/vector lane according to configured policy;
- lexical-worthy query retains lexical lane according to policy;
- query chunking/planning must not silently drop exact identifier/reference intent.

### Boundary

Assert `RetrievalPlan` capabilities/output, not private planner helper calls.

### Acceptance

The contract remains valid if planner internals are refactored but observable lane capabilities remain correct.

---

## 7.3 RA-03.2 — R-02 ACL isolation

### Invariant

No hit above the caller's allowed access boundary may survive into fused, reranked, expanded or final-context evidence.

### Required stage checks

```text
raw retrieval
 -> fused
 -> reranked
 -> expanded
 -> final context
```

At each relevant public boundary, inaccessible evidence MUST be absent or rejected before it can affect answer provenance.

Use `SecurityAssertions.hasNoAclLeak(...)`.

### Critical fixture

Restricted evidence MUST intentionally have a better raw score/rank than accessible evidence. The contract must prove security wins over relevance.

---

## 7.4 RA-03.3 — R-03 Published-generation isolation

### Invariant

Every readable/final evidence item belongs to a generation valid under current publication rules.

### Required cases

- published generation accepted;
- staged generation rejected;
- superseded generation rejected as current evidence;
- mixed-generation candidate list is normalized/rejected before final context;
- routing identity remains complete.

Use `LifecycleAssertions` plus production published-state predicate/reader rather than duplicating lifecycle logic.

---

## 7.5 RA-03.4 — R-04 Fusion canonicalization

### Invariant

The same canonical evidence returned by multiple lanes appears once after fusion, while provenance is preserved.

### Required cases

```text
VECTOR + LEXICAL -> one canonical hit with both evidence records
IDENTIFIER + VECTOR -> one canonical hit, exact authority retained
REFERENCE + VECTOR -> one canonical hit, exact authority retained
```

### Required checks

- no duplicate canonical identities;
- `RetrievalEvidence` includes all contributing lanes required by the merge;
- selected payload is deterministic;
- exact-authority payload is not lost because it arrived later/earlier;
- fusion ordering respects documented authority tier before soft weighting where applicable.

Use `RetrievalAssertions.hasNoDuplicateCanonicalHits()` and `hasRetrievalEvidence()`; extend with an authority assertion rather than copying fusion logic.

---

## 7.6 RA-03.5 — R-05 Exact identifier/reference authority

### Invariant

A weaker semantic candidate MUST NOT silently replace an exact identifier/reference authoritative payload for the same canonical evidence.

### Required cases

- identifier exact + semantic hit with conflicting payload metadata;
- reference exact + vector hit;
- exact authority survives fusion and reranking;
- provenance still records weaker contributing lanes without downgrading canonical authority.

### Acceptance

The final canonical hit can be traced to exact identifier/reference authority.

---

## 7.7 RA-03.6 — R-06 Reranker fallback

### Invariant

Reranker timeout/failure follows safe fallback behavior without candidate corruption or privilege broadening.

### Required cases

- timeout;
- exception;
- invalid/empty reranker response if supported by current adapter contract.

Expected fallback MUST prove:

- candidate identities preserved;
- no duplicates introduced;
- ACL/lifecycle eligibility not broadened;
- exact-authority evidence not discarded;
- deterministic fallback ordering according to current production contract.

### Negative case

`ReverseReranker` is killed in RA-07 where an authority/order guarantee exists.

---

## 7.8 RA-03.7 — R-07 Expansion safety

### Invariant

Graph/neighbor/semantic expansion MUST preserve all upstream safety boundaries.

### Required checks

Expanded evidence MUST preserve:

- ACL boundary;
- published lifecycle eligibility;
- canonical identity;
- generation routing identity;
- no duplicate canonical hits;
- downstream context-budget compatibility.

### Required negative fixture

An accessible seed points to an inaccessible/high-value neighbor. Expansion MUST NOT leak the neighbor into eligible/final evidence.

### Acceptance

Security/lifecycle checks are demonstrated **after expansion**, not only before expansion.

---

## 7.9 RA-03 deliverables

```text
read/QueryContractTest.java
read/RetrievalContractTest.java
read/FusionContractTest.java
read/RerankingContractTest.java
read/ExpansionContractTest.java
```

### RA-03 Definition of Done

```text
[ ] R-01 required query lanes retained
[ ] R-02 ACL isolation across retrieval/fusion/rerank/expansion
[ ] R-03 published generation isolation
[ ] R-04 canonical fusion + provenance
[ ] R-05 identifier/reference authority survives fusion/rerank
[ ] R-06 reranker failure is safe
[ ] R-07 expansion preserves security/lifecycle/canonical identity
```

---

# 8. RA-04 — READ context/answer contracts

## 8.1 Goal

Implement R-08 through R-12 for temporal authority, bounded context, late publication revalidation, citation validity and grounding/abstention.

## 8.2 RA-04.1 — R-08 Temporal authority

### Invariant

Expired, withdrawn, superseded or otherwise inactive evidence MUST NOT enter final context when production authority rules classify it as inactive.

### Test design

Use `TemporalAuthorityFilter` with a fixed `Clock`.

Required cases:

- active evidence retained;
- expired evidence removed;
- future-effective evidence follows current policy;
- superseded/withdrawn marker follows current production metadata contract;
- active lower-score evidence wins over inactive higher-score evidence.

### Acceptance

Time-dependent tests are reproducible and contain no system-clock flakiness.

---

## 8.3 RA-04.2 — R-09 Context budget

### Invariant

Final evidence passed toward generation MUST remain within configured context limits after fusion, reranking, expansion, filtering and formatting.

### Required cases

- under-budget context unchanged;
- over-budget candidate set truncated/assembled deterministically;
- expanded evidence cannot bypass budget;
- no blank evidence;
- final token estimate is within configured maximum.

Use `ContextAssertions.isWithinTokenBudget(...)` and `containsNoBlankEvidence()`.

### Acceptance

The test validates the final assembled evidence boundary rather than a pre-expansion list.

---

## 8.4 RA-04.3 — R-10 Published revalidation

### Invariant

Evidence that becomes stale/ineligible between retrieval and final context assembly MUST be rejected by the late published-state check.

### Required race-style scenario

```text
retrieve evidence from published generation N
 -> lifecycle changes / publication switches to N+1
 -> final revalidation
 -> stale N evidence rejected from final context
```

Use deterministic repositories/test doubles or Testcontainers according to the production revalidation seam.

### Acceptance

The scenario proves defense-in-depth and does not depend only on initial retrieval filtering.

---

## 8.5 RA-04.4 — R-11 Citation validity

### Invariant

Every generated `[SOURCE n]` marker MUST resolve to a source in the final bounded context.

### Required cases

- valid `[SOURCE 1]` accepted;
- nonexistent `[SOURCE 999]` rejected;
- duplicate citation markers behave deterministically;
- answer with source marker outside final context rejected;
- source response mapping remains deterministic.

Extend `CitationAssertions` only for reusable properties such as `hasOnlyCitedSources(...)`.

### Acceptance

Citation validation is exercised against **final context**, not raw retrieval candidates.

---

## 8.6 RA-04.5 — R-12 Grounding or abstention

### Invariant

Unsupported output MUST NOT be returned as a successful grounded answer.

Acceptable terminal behavior:

```text
grounded answer
OR
explicit abstention/rejection
```

### Mandatory cases

1. fully supported factual statement passes;
2. uncited factual statement is rejected according to grounding rules;
3. numeric drift is rejected;
4. unsupported dosage/amount/date claim is rejected;
5. invalid citation causes rejection before/with grounding;
6. explicit abstention is accepted by `isGroundedOrAbstains(true)`.

### Acceptance

A test MUST prove that unsupported factual content cannot reach a successful grounded response merely because retrieval returned related context.

---

## 8.7 RA-04 deliverables

```text
read/ContextContractTest.java
read/CitationContractTest.java
read/GroundingContractTest.java
```

### RA-04 Definition of Done

```text
[ ] R-08 inactive temporal evidence excluded
[ ] R-09 final context bounded
[ ] R-10 late publication revalidation enforced
[ ] R-11 invalid citations rejected
[ ] R-12 unsupported output grounded-or-abstains
[ ] fixed clock used for temporal tests
[ ] no live LLM dependency
```

---

# 9. RA-05 — Domain fixtures

## 9.1 Goal

Create deterministic legal and medical fixtures in Kazakh, Russian and English that drive the same reusable contracts.

## 9.2 Minimum fixture matrix

Each cell MUST contain at least one positive and one negative/edge fixture.

| Domain | kk | ru | en |
|---|---:|---:|---:|
| LEGAL | 2+ | 2+ | 2+ |
| MEDICAL | 2+ | 2+ | 2+ |

Minimum total: **12 fixtures**.

Additional targeted fixtures may be added for identifiers, publication and security.

## 9.3 Fixture schema v1

Recommended YAML structure:

```yaml
schemaVersion: 1
id: legal-ru-001
language: ru
domain: LEGAL
documentId: legal-ru-001
accessLevel: 10
generation: 1
published: true

sourceText: |
  ...

requiredFactsTogether:
  - ["расторгнуть договор", "просрочка более 30 дней"]

requiredIdentifiers:
  - KZ-2026-001847

requiredReferences:
  - article-25

forbiddenSplits:
  - ["10 мг", "один раз в сутки"]

requiredMetadata:
  language: ru
  domain: LEGAL

expectedSafety:
  mustRemainPublishedOnly: true
  mustRemainAclIsolated: true
```

Fields irrelevant to a fixture MAY be empty/omitted, but loader validation MUST reject malformed mandatory identity fields.

## 9.4 Fixture rules

- use native-language fixture text, not machine-generated expected prose;
- preserve locale-specific punctuation/number formatting where relevant;
- do not encode exact LLM answer text;
- encode properties, not giant snapshots;
- every fixture ID is globally unique;
- every failure reports fixture ID;
- numeric medical fixtures must make dose/unit/route/frequency unambiguous;
- legal fixtures must include at least one rule+exception pair in each language.

## 9.5 Fixture loader

Create a small test-only loader, e.g. `RagContractFixtureLoader`, using the project's existing Jackson/YAML support.

Loader responsibilities:

- deserialize schema v1;
- validate fixture identity;
- expose typed required facts/identifiers/references/metadata;
- provide actionable parse errors including resource path and fixture ID where available.

It MUST NOT implement RAG logic.

## 9.6 Scenario coverage

Create:

```text
scenarios/LegalPipelineContractTest.java
scenarios/MedicalPipelineContractTest.java
scenarios/MultilingualPipelineContractTest.java
```

The same correctness guarantees SHOULD be parameterized across language fixtures where the production feature supports them.

### RA-05 Definition of Done

```text
[ ] LEGAL kk positive + edge
[ ] LEGAL ru positive + edge
[ ] LEGAL en positive + edge
[ ] MEDICAL kk positive + edge
[ ] MEDICAL ru positive + edge
[ ] MEDICAL en positive + edge
[ ] legal rule+exception represented in all 3 languages
[ ] medical drug+dose+route+frequency represented in all 3 languages
[ ] fixture loader validates schema
[ ] scenarios assert properties rather than exact LLM prose
```

---

# 10. RA-06 — Security isolation

## 10.1 Goal

Prove ACL isolation across the complete READ path rather than at a single repository query.

## 10.2 Mandatory scenario

Create `SecurityIsolationContractTest` with at least:

```text
caller allowed access = {10}

accessible evidence:
  accessLevel = 10
  raw relevance = lower

restricted evidence:
  accessLevel = 99
  raw relevance = higher
```

The restricted evidence SHOULD be made attractive enough that it would win relevance/ranking if ACL were bypassed.

## 10.3 Cross-stage assertions

Verify no restricted identity survives into:

1. eligible retrieval results after security boundary;
2. fused output;
3. reranked output;
4. expansion output;
5. final context;
6. cited source mapping;
7. answer provenance / learning signal where observable.

## 10.4 Expansion-specific case

An accessible seed MUST link to an inaccessible neighbor. Expansion must reject the inaccessible neighbor.

## 10.5 Fail-closed behavior

If required ACL identity/routing metadata is missing or malformed at a security-critical boundary, the contract SHOULD expect rejection rather than permissive inclusion, matching production policy.

## 10.6 Acceptance

The test MUST fail if `AclBypassRetriever` or equivalent security bypass is substituted.

### RA-06 Definition of Done

```text
[ ] high-ranked restricted evidence cannot reach final context
[ ] expansion cannot introduce inaccessible neighbor
[ ] fusion/reranking cannot reintroduce restricted evidence
[ ] citations/provenance contain no restricted source
[ ] missing critical ACL identity follows fail-closed behavior
```

---

# 11. RA-07 — Mutation smoke

## 11.1 Goal

Demonstrate that the contract suite detects representative violations of critical RAG guarantees.

This phase does **not** require PIT or another mutation-testing dependency.

## 11.2 Required explicit mutants

```text
ReverseReranker
AclBypassRetriever
CitationAcceptAll
BrokenFusion
AtomicFactSplitter
GenerationIgnoringReader
```

Mutants live under test sources only.

## 11.3 Mutation design rule

Do not write separate fake assertions specifically for mutants. A mutant must feed bad observable behavior into the **same reusable contract assertion/harness** used by normal tests.

Recommended pattern:

```java
AssertionError error = assertThrows(
        AssertionError.class,
        () -> RagContractHarness.assertSecurityIsolation(mutatedPipelineOutput, fixture)
);
```

or equivalent direct `RagAssertions` composition.

The important property is:

```text
broken behavior
 -> normal contract logic
 -> deterministic contract failure
```

## 11.4 Kill matrix

| Mutant | Broken guarantee | Expected killer |
|---|---|---|
| `ReverseReranker` | guaranteed authority/order behavior reversed | R-05/R-06 contract where authority ordering is mandatory |
| `AclBypassRetriever` | inaccessible hit admitted | R-02 / RA-06 security isolation |
| `CitationAcceptAll` | invalid marker accepted | R-11 citation contract |
| `BrokenFusion` | duplicate/provenance/authority lost | R-04 fusion contract |
| `AtomicFactSplitter` | protected fact fragmented | W-06 atomic fact contract |
| `GenerationIgnoringReader` | unpublished/superseded generation read | W-09 / R-03 / R-10 lifecycle contracts |

## 11.5 Required meta-test

`MutationSmokeTest` MUST cover every required mutant and fail if a mutant is no longer detected by its expected contract.

The test output SHOULD identify:

- mutant name;
- expected killing contract;
- fixture;
- observed reason for kill.

## 11.6 Acceptance

All six mutants are killed deterministically with no external service dependency.

### RA-07 Definition of Done

```text
[ ] ReverseReranker killed
[ ] AclBypassRetriever killed
[ ] CitationAcceptAll killed
[ ] BrokenFusion killed
[ ] AtomicFactSplitter killed
[ ] GenerationIgnoringReader killed
[ ] kill matrix documented and executable
[ ] no PIT dependency required
```

---

# 12. RA-08 — CI and documentation hardening

## 12.1 Goal

Make assurance contracts an unavoidable, diagnosable part of normal development and merge validation.

## 12.2 Maven/CI gate

Required merge command remains:

```bash
mvn clean verify
```

The assurance suite MUST run through the normal verification path. Developers must not need a hidden/manual command to get the correctness result used by CI.

## 12.3 External dependencies

Core assurance contracts MUST NOT require:

- live Ollama;
- external HTTP services;
- nondeterministic model inference.

PostgreSQL/Testcontainers is allowed only for contracts whose invariant is persistence/lifecycle/profile-specific.

## 12.4 Optional JUnit tags

If useful for diagnostics, tests MAY use tags such as:

```text
rag-contract
rag-integration
rag-scenario
rag-mutation
```

But tags MUST NOT cause the default `mvn clean verify` path to skip required assurance coverage.

## 12.5 CI diagnostics

On failure, logs MUST make it possible to identify:

- contract ID;
- fixture ID;
- failing stage;
- offending chunk/hit/source/generation/profile;
- expected vs actual safety property.

## 12.6 Documentation deliverables

Add/update:

```text
docs/quality/rag-assurance-testing.md
```

It SHOULD document:

1. purpose of contract tests vs quality benchmark vs performance harness;
2. package structure;
3. how to run all assurance tests locally;
4. how to run a focused contract class;
5. fixture schema and naming rules;
6. how to add a new language/domain fixture;
7. mutation smoke workflow;
8. rules for deterministic clocks/test doubles;
9. prohibition on live Ollama in core assurance;
10. troubleshooting common Testcontainers/fixture failures.

## 12.7 Remove duplication

Where existing assurance/scenario tests duplicate low-level checks now provided by `RagAssertions`, migrate them to reusable assertions when doing so improves diagnostics and does not reduce coverage.

Do not perform unrelated test-suite cleanup in this branch.

## 12.8 CI completion criteria

At minimum the branch is ready to merge when:

```text
Formatting       PASS
mvn clean verify PASS
```

and all required RA-02…RA-07 contracts are included in that verify result.

### RA-08 Definition of Done

```text
[ ] assurance suite executes under mvn clean verify
[ ] no live Ollama dependency
[ ] deterministic time/model collaborators
[ ] fixture conventions documented
[ ] local run commands documented
[ ] mutation smoke documented
[ ] failure diagnostics actionable
[ ] reusable assertions used instead of duplicated plumbing
```

---

# 13. Contract-to-test matrix

| Contract | Primary test | Layer | Main reusable assertion |
|---|---|---:|---|
| W-01 | `NormalizationContractTest` | A | equality/property assertions |
| W-02 | `ChunkingContractTest` | A | `ChunkAssertions` |
| W-03 | `ChunkingContractTest` | A | `containsNoEmptyChunks()` |
| W-04 | `ChunkingContractTest` | A | `respectsHardTokenLimit(...)` |
| W-05 | `MetadataContractTest` | A | `ChunkAssertions` / metadata extension |
| W-06 | `AtomicFactContractTest` | A/C | `preservesAtomicFact(...)` |
| W-07 | `IdentifierContractTest` | A/B | identifier authority assertion |
| W-08 | `ReferenceContractTest` | A/B | reference authority assertion |
| W-09 | `PublicationContractTest` | B | `LifecycleAssertions` |
| W-10 | `EmbeddingProfileContractTest` | B | embedding-profile assertion |
| R-01 | `QueryContractTest` | A | plan capability assertions |
| R-02 | `RetrievalContractTest` + security scenario | A/C | `SecurityAssertions` |
| R-03 | `RetrievalContractTest` | A/B | `LifecycleAssertions` |
| R-04 | `FusionContractTest` | A | `RetrievalAssertions` |
| R-05 | `FusionContractTest` / `RerankingContractTest` | A | authority assertion |
| R-06 | `RerankingContractTest` | A | retrieval-set invariants |
| R-07 | `ExpansionContractTest` | A/C | security/lifecycle/retrieval assertions |
| R-08 | `ContextContractTest` | A | temporal assertion/filter output |
| R-09 | `ContextContractTest` | A | `ContextAssertions` |
| R-10 | `ContextContractTest` | B/C | `LifecycleAssertions` |
| R-11 | `CitationContractTest` | A/C | `CitationAssertions` |
| R-12 | `GroundingContractTest` | A/C | `GroundingAssertions` |

---

# 14. Implementation and commit sequence

The branch SHOULD be implemented as reviewable, independently diagnosable commits.

Recommended sequence:

```text
RA-02.1  test: add W-01 normalization determinism contract
RA-02.2  test: add W-02/W-03 chunk identity and non-empty contracts
RA-02.3  test: add W-04 final token envelope contract
RA-02.4  test: add W-05 metadata preservation contract
RA-02.5  test: add W-06 protected atomic fact contracts
RA-02.6  test: add W-07/W-08 identifier and reference authority contracts
RA-02.7  test: add W-09 publication isolation integration contract
RA-02.8  test: add W-10 embedding profile consistency contract

RA-03.1  test: add R-01 query lane intent contract
RA-03.2  test: add R-02/R-03 retrieval isolation contracts
RA-03.3  test: add R-04/R-05 fusion authority contracts
RA-03.4  test: add R-06 reranker fallback contract
RA-03.5  test: add R-07 expansion safety contract

RA-04.1  test: add R-08 temporal authority contract
RA-04.2  test: add R-09/R-10 context and revalidation contracts
RA-04.3  test: add R-11 citation contract
RA-04.4  test: add R-12 grounding and abstention contract

RA-05.1  test: add typed YAML fixture loader and schema
RA-05.2  test: add legal kk/ru/en fixture matrix
RA-05.3  test: add medical kk/ru/en fixture matrix
RA-05.4  test: add multilingual/domain scenarios

RA-06.1  test: add cross-stage ACL security isolation scenario

RA-07.1  test: add assurance mutants
RA-07.2  test: add executable mutation kill matrix

RA-08.1  docs: document RAG assurance execution and fixture conventions
RA-08.2  ci: harden assurance verification and diagnostics if required
```

Combining adjacent items is acceptable where the resulting commit remains conceptually atomic.

---

# 15. Pull request strategy

Although development occurs in one branch, implementation SHOULD remain reviewable by phase.

If the final diff becomes too large, split delivery into stacked/sequential PRs while preserving the same contract IDs:

```text
PR A: RA-02 WRITE
PR B: RA-03 + RA-04 READ
PR C: RA-05 + RA-06 scenarios/fixtures/security
PR D: RA-07 + RA-08 mutation/CI/docs
```

Do not merge a phase that weakens an already-green RA-01 assertion foundation.

---

# 16. Global Definition of Done for RA-02…RA-08

The work is complete only when all items below are true:

```text
WRITE
[ ] W-01 through W-10 implemented
[ ] final embedding payload bounded
[ ] atomic legal/medical facts protected
[ ] identifier/reference authority protected
[ ] publication isolation proven with generation transitions
[ ] incompatible embedding profiles cannot mix

READ
[ ] R-01 through R-12 implemented
[ ] query lane intent protected
[ ] ACL and lifecycle preserved through expansion
[ ] canonical fusion preserves provenance and exact authority
[ ] reranker fallback safe
[ ] temporal authority enforced
[ ] final context bounded
[ ] late publication revalidation enforced
[ ] invalid citations rejected
[ ] unsupported output grounded-or-abstains

FIXTURES / SCENARIOS
[ ] LEGAL kk/ru/en positive + edge fixtures
[ ] MEDICAL kk/ru/en positive + edge fixtures
[ ] multilingual scenarios use same reusable contracts
[ ] security scenario contains higher-ranked restricted evidence

MUTATION
[ ] all 6 required mutants killed
[ ] kill matrix executable and documented

ENGINEERING
[ ] no private-method coupling
[ ] no copied production algorithms in tests
[ ] no live Ollama dependency
[ ] deterministic clocks/collaborators
[ ] failures include contract and fixture identity
[ ] mvn clean verify passes from clean checkout
[ ] assurance testing documentation complete
```

---

# 17. Non-goals and separation from future branches

This branch MUST NOT become the quality benchmark or performance harness.

### Not part of RA-02…RA-08

- Recall@K;
- MRR;
- nDCG;
- ranking threshold promotion gates;
- retrieval weight tuning;
- live corpus benchmark;
- p50/p95/p99 latency baselines;
- throughput/saturation testing;
- production performance optimization;
- exact LLM response snapshots.

After this work is merged and stable:

```text
RAG assurance RA-02…RA-08
        |
        v
rag-benchmark-v1
        |
        v
performance harness
```

The engineering questions remain separate:

```text
Assurance contracts  -> Is the system correct and safe by invariant?
Quality benchmark    -> Is retrieval/answer quality better or worse?
Performance harness  -> What latency/capacity cost does the system have?
```

---

# 18. Expected outcome

After RA-02…RA-08, AkmAI should have an executable correctness fence around both RAG directions:

```text
WRITE
input
 -> deterministic normalization
 -> safe chunking
 -> attributable enrichment
 -> bounded embeddings
 -> generation-safe publication

READ
query
 -> intended retrieval lanes
 -> ACL/lifecycle-safe evidence
 -> canonical fusion
 -> safe rerank/expansion
 -> temporal filtering
 -> bounded final context
 -> valid citations
 -> grounded answer or abstention
```

A critical regression must produce a small, diagnostic, contract-specific RED failure before it reaches production or before quality/performance benchmark results are trusted.
