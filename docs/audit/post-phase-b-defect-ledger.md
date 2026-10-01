# Post-Phase-B Defect Remediation Ledger

Branch: `fix/post-phase-b-defect-remediation`

Purpose: accumulate defects found by repeated deep audits and close them with reproducible tests and exact-SHA CI evidence.

## Audit cadence

- Audit 1/10: completed — initial deep audit after Phase B
- Audits 2/10 .. 10/10: pending
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

## Remediation order

### Wave 1 — consistency and visibility

```text
D01 orphan vectors
D02 published-generation visibility
D03 non-destructive replacement
D19 vector identity canonicalization
```

### Wave 2 — retrieval correctness

```text
D04 canonical representative
D05 identifier-only canonical resolution
D06 RU/EN FTS indexes
D07 retrieval deadlines
D08 saturation isolation
```

### Wave 3 — lifecycle/concurrency

```text
D09 retention shutdown race
```

### Wave 4 — trust, provenance and API boundaries

```text
D10 prompt injection boundary
D11 citation requirement
D12 citation sanitization
D13 provenance contract
D14 expansion provenance
D15 language canonicalization
D16 ambiguous language routing
D17 request limits
D18 authentication/authorization
```

## Required final gate

```text
all P0/P1 = VERIFIED
all accepted P2 have explicit disposition
full Testcontainers suite
spotless
mvn clean verify
exact-SHA GitHub Actions success
Audit 10/10 completed
```
