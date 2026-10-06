# RAG Assurance Contracts — Technical Specification

**Status:** Proposed  
**Branch:** `feature/rag-assurance-contracts`  
**Scope:** correctness assurance for the production WRITE and READ RAG pipelines  
**Out of scope:** retrieval tuning, benchmark quality scoring, performance optimization

## 1. Objective

Introduce a stable assurance layer that verifies **observable RAG guarantees** rather than private implementation details.

The implementation may evolve, but the following contract must remain true:

```text
implementation may change
        |
        v
RAG invariants remain stable
        |
        v
contract tests prove correctness
```

This work is the correctness foundation for later, independent work on:

1. `rag-benchmark-v1` for retrieval quality;
2. the performance harness for latency, throughput, and saturation.

The contract suite MUST fail when a critical RAG guarantee is intentionally broken.

---

## 2. Existing production boundaries to protect

The specification is anchored in the current AkmAI architecture and must not replace it.

### WRITE path

```text
document
 -> normalize
 -> structure
 -> semantic units
 -> chunk
 -> enrich
 -> identifiers/references
 -> embedding
 -> persistence
 -> publication
```

Primary existing implementation boundaries include the chunking/enrichment pipeline, generation-scoped persistence, identifier/reference projections, embedding configuration, and publication lifecycle.

### READ path

```text
question
 -> query analysis
 -> retrieval planning
 -> retrieval lanes
 -> fusion
 -> rerank
 -> expansion
 -> temporal/authority filtering
 -> context budgeting
 -> published revalidation
 -> generation
 -> citation validation
 -> grounding
```

Existing production components that contracts should exercise through public behavior include, among others:

- `RagQuestionService`;
- `ResultFusion`;
- `Reranker`;
- `TemporalAuthorityFilter`;
- `ContextBudget` / context assembly boundaries;
- `CitationValidator`;
- `AnswerGroundingVerifier`;
- `SemanticChunker` and related chunking/enrichment services.

Tests MUST NOT depend on private methods or reproduce production algorithms inside test code.

---

## 3. Design principles

### 3.1 Contract over implementation

A contract test should assert outcomes such as:

- no ACL leakage;
- only published generations can become answer evidence;
- exact identifiers retain authority;
- canonical duplicate hits are fused correctly;
- final context stays inside its budget;
- invalid citations are rejected;
- unsupported generated content is rejected or causes abstention.

It should not assert:

- the exact private call graph;
- internal collection types;
- private helper invocation counts;
- incidental ordering unless ordering itself is a contract;
- implementation-specific scores that are not externally guaranteed.

### 3.2 Fail closed for safety boundaries

Contracts around ACL, publication lifecycle, authority/temporal validity, citations, and grounding MUST fail closed.

### 3.3 Deterministic fixtures

Fixtures MUST be deterministic, version-controlled, small enough for fast diagnosis, and written in terms of required properties rather than one giant expected-output snapshot.

### 3.4 Reusable assertions

Common rules MUST be implemented once in reusable assertion classes. Individual tests should read like domain contracts, not low-level assertion plumbing.

---

## 4. Test package structure

Target structure:

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

    write/
        NormalizationContractTest.java
        ChunkingContractTest.java
        IdentifierContractTest.java
        ReferenceContractTest.java
        EmbeddingContractTest.java
        PublicationContractTest.java

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
```

Target resource structure:

```text
src/test/resources/rag-contracts/
    legal/
        ru/
        kk/
        en/
    medical/
        ru/
        kk/
        en/
    technical/
        ru/
        kk/
        en/
```

`RagAssertions` MUST remain a facade. It MUST NOT become a monolithic god-class containing every assertion implementation.

---

## 5. `RagAssertions` API

Illustrative API:

```java
RagAssertions.assertThat(chunks)
        .respectsHardTokenLimit()
        .preservesDocumentIdentity()
        .hasStableChunkIds()
        .containsNoEmptyChunks();

RagAssertions.assertThat(result)
        .hasNoAclLeak(accessLevels)
        .containsPublishedGenerationsOnly()
        .hasValidCitations()
        .isWithinContextBudget();
```

Required assertion capabilities:

| Assertion | Contract protected |
|---|---|
| `hasNoAclLeak()` | security boundary |
| `containsPublishedGenerationsOnly()` | lifecycle/generation consistency |
| `hasSingleEmbeddingProfile()` | no mixed embedding spaces |
| `respectsHardTokenLimit()` | chunk/embedding envelope |
| `preservesAtomicFact()` | protected legal/medical fact integrity |
| `preservesIdentifierAuthority()` | exact identifier authority |
| `preservesReferenceAuthority()` | exact reference authority |
| `hasNoDuplicateCanonicalHits()` | fusion/canonicalization |
| `hasRetrievalEvidence()` | retrieval provenance |
| `isWithinContextBudget()` | bounded context |
| `containsNoExpiredEvidence()` | temporal authority |
| `hasValidCitations()` | citation integrity |
| `hasOnlyCitedSources()` | response provenance |
| `isGroundedOrAbstains()` | answer safety boundary |

Assertion names may be refined during implementation, but the protected guarantees MUST remain represented.

---

## 6. WRITE contracts

### W-01 Normalization determinism

Given the same canonical input and the same configuration, normalization MUST produce equivalent canonical text and metadata.

### W-02 Stable chunk identity

Given the same canonical document input and the same chunking contract, chunk identity MUST be deterministic.

A refactor that changes a private helper but preserves canonical chunk boundaries must not fail this contract.

### W-03 Non-empty chunks

No persisted/published chunk may contain blank canonical content or blank embedding content.

### W-04 Hard token envelope

Every final chunk and embedding payload MUST respect the configured hard token/model envelope after all enrichment is applied.

The contract MUST validate the final payload that is actually sent toward embedding/persistence, not an intermediate pre-enrichment string.

### W-05 Metadata preservation

Required document/chunk metadata MUST survive the WRITE pipeline, including at minimum the identity and classification fields required by READ-time filtering and attribution.

### W-06 Protected atomic facts

Protected facts MUST not be split in a way that destroys their meaning when a valid boundary can preserve them.

Required initial fixtures:

```text
LEGAL:
  RULE + EXCEPTION

MEDICAL:
  DRUG + DOSE + ROUTE + FREQUENCY
```

Example forbidden split property:

```yaml
forbiddenSplits:
  - ["10 mg", "once daily"]
```

### W-07 Identifier authority

Canonical identifier values MUST remain stable through enrichment and persistence, and an identifier MUST retain the correct document/chunk ownership.

### W-08 Reference authority

References MUST resolve to the correct owning document/chunk and MUST not be silently reassigned by chunking/enrichment changes.

### W-09 Publication isolation

Unpublished or superseded generation data MUST NOT become readable answer evidence through normal READ paths.

### W-10 Embedding profile consistency

A single retrieval operation MUST NOT combine incompatible embedding spaces/profiles as if they were comparable vectors.

---

## 7. READ contracts

### R-01 Query lane intent

Identifier queries MUST retain an identifier-capable retrieval path. Semantic queries MUST retain the retrieval lanes required by configured policy.

The test should assert the planned capability, not private planner implementation details.

### R-02 ACL isolation

No retrieved, expanded, fused, reranked, or final-context hit may exceed the caller's permitted access boundary.

This contract MUST cover expansion as well as initial retrieval.

### R-03 Published-generation isolation

Every final evidence item MUST belong to a generation that is valid for reading under the current publication/lifecycle contract.

### R-04 Fusion canonicalization

When the same canonical chunk is returned by multiple retrieval lanes:

- it MUST appear once as a canonical result;
- contributing retrieval evidence/provenance MUST be preserved;
- exact-authority evidence MUST not be lost due to arbitrary first-hit selection.

### R-05 Exact identifier/reference authority

Exact identifier/reference hits MUST retain their authority semantics through fusion and reranking.

A weaker semantic hit MUST NOT silently replace an exact authoritative payload for the same canonical evidence.

### R-06 Reranker fallback

A reranker timeout/failure MUST follow the defined safe fallback behavior and MUST NOT corrupt, duplicate, or broaden the candidate set.

### R-07 Expansion safety

Neighbor/graph/semantic expansion MUST preserve:

- ACL boundaries;
- publication/lifecycle validity;
- canonical identity;
- context budgeting constraints downstream.

### R-08 Temporal authority

Explicitly expired, superseded, withdrawn, or otherwise invalid evidence MUST NOT enter final context when the production authority rules classify it as inactive.

### R-09 Context budget

The final context passed to generation MUST stay within the configured context/token budget after all expansion, filtering, and formatting.

### R-10 Published revalidation

Evidence that became stale/ineligible between initial retrieval and final context assembly MUST be rejected at revalidation.

### R-11 Citation validity

Generated source markers MUST reference an existing source in the final bounded context.

At minimum, the suite MUST prove that an invalid marker such as:

```text
[SOURCE 999]
```

is rejected.

### R-12 Grounding or abstention

Unsupported output MUST NOT be returned as a successful grounded answer.

The acceptable terminal behavior is:

```text
grounded answer
OR
abstention / rejected answer
```

The suite MUST include factual, numeric, and uncited failure cases.

---

## 8. Scenario contracts

Scenario tests verify cross-component guarantees without turning into a full retrieval benchmark.

### 8.1 Legal scenario

Minimum scenario properties:

- rule and exception remain jointly recoverable;
- exact article/reference remains attributable;
- inactive/superseded authority does not displace active authority;
- answer citations point only to final context evidence.

### 8.2 Medical scenario

Minimum scenario properties:

- drug, dose, route, and frequency remain semantically intact;
- numeric drift is rejected by grounding;
- unsupported dosage claims cannot pass as grounded.

### 8.3 Multilingual scenario

Initial required languages:

```text
kk
ru
en
```

The contract suite MUST prove the same safety/correctness guarantees across these languages where the production feature supports them.

The multilingual contract is not a ranking benchmark and MUST NOT assert global Recall/MRR/nDCG targets.

### 8.4 Security isolation scenario

Build at least one end-to-end READ scenario where inaccessible evidence ranks highly before security filtering. The final context and answer provenance MUST contain no restricted evidence.

---

## 9. Golden fixture format

Do not store only opaque `expected-output.json` snapshots.

Fixtures should express required and forbidden properties, for example:

```yaml
id: legal-ru-001
language: ru
domain: LEGAL
documentId: legal-ru-001

requiredFactsTogether:
  - ["расторгнуть договор", "просрочка более 30 дней"]

requiredIdentifiers:
  - KZ-2026-001847

requiredReferences:
  - article-25

forbiddenSplits:
  - ["10 мг", "один раз в сутки"]
```

Recommended common fields:

```yaml
id:
language:
domain:
documentId:
accessLevel:
generation:
published:
requiredFactsTogether: []
requiredIdentifiers: []
requiredReferences: []
forbiddenSplits: []
requiredMetadata: {}
```

Fixtures MUST be self-explanatory enough that a failed contract can report the violated property and fixture id.

---

## 10. Mutation smoke tests

The first version MUST use explicit intentional mutants/test doubles rather than introducing a new mutation-testing framework solely for this branch.

Required mutants:

```text
ReverseReranker
AclBypassRetriever
CitationAcceptAll
BrokenFusion
AtomicFactSplitter
GenerationIgnoringReader
```

Required meta-contract:

> If a critical guarantee is intentionally broken, at least one corresponding assurance contract MUST fail.

Expected kill matrix:

| Mutant | Expected detecting contract |
|---|---|
| `ReverseReranker` | reranking/fallback/order contract where authority order is guaranteed |
| `AclBypassRetriever` | ACL isolation |
| `CitationAcceptAll` | citation validity |
| `BrokenFusion` | canonicalization + provenance |
| `AtomicFactSplitter` | protected atomic fact |
| `GenerationIgnoringReader` | publication isolation |

Mutation smoke tests MUST remain deterministic and MUST NOT require external model availability.

A later decision may add PIT or another mutation framework, but that is not a prerequisite for this branch.

---

## 11. Test layering

Use three layers.

### Layer A — pure contract/unit

Fast deterministic contracts around value objects and components. No PostgreSQL/Ollama.

### Layer B — component/integration

Use Spring/Testcontainers only where persistence, lifecycle, pgvector projections, or transaction boundaries are part of the contract.

### Layer C — scenario

Exercise the public WRITE/READ orchestration with deterministic collaborators where external model nondeterminism would make the correctness contract unstable.

The branch MUST NOT convert correctness contracts into a quality benchmark. Real retrieval quality belongs in `rag-benchmark-v1`.

---

## 12. CI contract

### Pull requests

PR validation MUST execute the deterministic assurance suite with the normal project verification path.

Required merge condition:

```text
mvn clean verify
```

must remain green, including the new assurance contracts.

### External model dependency

The core assurance suite MUST NOT require a live Ollama model. Tests that require real model behavior belong to existing/live quality workflows or the future benchmark branch.

### Failure diagnostics

Every contract failure SHOULD report:

- contract id/name;
- fixture/scenario id;
- expected invariant;
- violating chunk/hit/source id when available;
- enough metadata to reproduce the failure locally.

---

## 13. Work breakdown

### RA-01 — Assertion foundation

- create `rag.assurance` package;
- implement `RagAssertions` facade;
- implement typed assertion classes;
- add failure-message conventions.

**Acceptance:** at least chunk, retrieval, context, citation, grounding, lifecycle, and security assertions are reusable by multiple tests.

### RA-02 — WRITE contracts

Implement W-01 through W-10 with deterministic fixtures.

**Acceptance:** critical WRITE guarantees are covered without private-method coupling.

### RA-03 — READ retrieval/fusion contracts

Implement R-01 through R-07.

**Acceptance:** retrieval provenance, canonicalization, authority preservation, fallback, expansion safety, and ACL behavior are covered.

### RA-04 — READ context/answer contracts

Implement R-08 through R-12.

**Acceptance:** temporal authority, context budget, revalidation, citation validation, grounding/abstention are covered.

### RA-05 — Domain fixtures

Create legal and medical fixtures for `kk`, `ru`, and `en`.

**Acceptance:** each required language has at least one positive and one negative/edge contract scenario per required domain class.

### RA-06 — Security isolation

Create cross-stage ACL scenario tests covering retrieval and expansion.

**Acceptance:** intentionally high-ranked inaccessible evidence never reaches final context/provenance.

### RA-07 — Mutation smoke

Add the explicit mutants and a kill matrix test strategy.

**Acceptance:** every required mutant is caught by at least one critical contract.

### RA-08 — CI/documentation hardening

- ensure contracts run under `mvn clean verify`;
- document local execution and fixture conventions;
- remove brittle duplicate assertions from scenario tests where reusable assertions exist.

**Acceptance:** clean checkout can execute the suite without Ollama.

---

## 14. Non-goals

This branch MUST NOT:

- tune retrieval weights;
- introduce Recall/MRR/nDCG gates;
- create `rag-benchmark-v1`;
- optimize latency/throughput;
- add performance baselines;
- redesign production retrieval architecture;
- replace existing hybrid retrieval, ACL, lifecycle, or Adaptive Graph behavior;
- assert exact LLM prose;
- introduce a mutation-testing dependency unless needed by a separately approved change.

---

## 15. Definition of Done

The branch is complete when all conditions below are met:

```text
[ ] reusable RagAssertions facade exists
[ ] assertion implementations are split by responsibility

[ ] WRITE critical invariants are covered
[ ] READ critical invariants are covered

[ ] kk / ru / en are represented
[ ] legal + medical scenarios are represented

[ ] ACL isolation is covered across retrieval and expansion
[ ] publication/generation isolation is covered
[ ] exact identifier/reference authority is covered
[ ] fusion provenance/canonicalization is covered
[ ] context budget and revalidation are covered
[ ] invalid citations are rejected
[ ] unsupported output is grounded-or-abstains

[ ] tests do not depend on private/internal method calls
[ ] core assurance suite does not require live Ollama

[ ] intentional mutants are killed by the contract suite

[ ] mvn clean verify passes
```

---

## 16. Follow-up branches

Only after this correctness layer is stable:

```text
feature/rag-assurance-contracts
        |
        v
merge to main
        |
        v
feature/rag-benchmark-v1
        |
        v
merge to main
        |
        v
feature/rag-performance-harness
```

The three branches answer different engineering questions:

```text
Assurance contracts  -> Is the RAG system correct and safe by contract?
Quality benchmark    -> Is retrieval quality better or worse?
Performance harness  -> What latency/capacity cost does the configuration have?
```
