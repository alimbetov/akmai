# AkmAI post-v1.1 readiness audit

Audit date: **2026-10-08**  
Audited ref: `main@607e4641db9270c74be57bba7167d3ed3ffc85a4`  
Implementation merge: PR #57 (`feat: self-optimizing RAG platform v1.1`)  
CI contract hotfix: PR #59 (`fix: align v1.1 deadline test contracts`)

## Executive conclusion

AkmAI is now a **production-oriented local-first multilingual evidence RAG engine** with a strong correctness and self-optimization architecture. The v1.1 implementation is complete at the code level and the normal `main` CI path is green on the audited ref.

The project is **not yet release-qualified for external production claims** because release qualification evidence is still missing from the repository/release record:

1. an approved immutable quality baseline has not yet been checked in under `benchmarks/rag-benchmark-v1/baselines/approved.json`;
2. the integrated `RAG v1.1 Release Qualification` workflow still needs a recorded successful run against live Ollama;
3. SMALL and MEDIUM performance evidence must be retained from the target/reference hardware profile;
4. semantic-grounding calibration must pass with retained RU/KK/EN evidence;
5. the default branch currently has no enforced branch protection / required status checks;
6. the controlled synthetic benchmark is suitable for regression and release engineering, but not for external real-world legal/medical/technical accuracy claims.

Recommended release state: **CONDITIONAL GO for internal/pilot use; NO-GO for externally claimed production qualification until the release gates below are complete.**

## Readiness scorecard

| Area | Status | Assessment |
|---|---|---|
| RAG architecture | READY | Hybrid retrieval, semantic chunking, reranking, bounded context, grounding and lifecycle separation are mature. |
| Retrieval correctness | READY | VECTOR / LEXICAL / IDENTIFIER / REFERENCE / CONCEPT paths, fusion, authority and published-generation fences are implemented. |
| Security / ACL | READY | Retrieval remains access-scoped; non-local startup fails closed when security is disabled or local bypass is enabled. |
| Lifecycle / retention | READY | Published retrieval eligibility includes synchronous TTL expiry fencing. |
| Request boundary | READY | Request body size is enforced while consuming the stream, including unknown/chunked bodies. |
| Re-embedding HA | READY | DB-backed owner lease, expiry and fencing token are implemented. |
| Resource deadlines | READY | Owned cancellable tasks, deadline-budget checks, JDBC/model transport bounds and typed timeout telemetry are implemented. |
| Self-optimizing memory | READY WITH GUARDS | Persistent Query Memory, policy isolation, feedback controls and source/query anti-poisoning are implemented. |
| Adaptive routing | READY WITH GUARDS | Candidate -> SHADOW -> CANARY -> APPROVED/ROLLBACK lifecycle is evidence-gated and bounded. |
| Semantic grounding | IMPLEMENTED / CALIBRATION PENDING | Deterministic grounding remains authoritative; semantic verifier is fail-closed when enabled. Live calibration evidence is still required. |
| CanonicalDocument provenance | READY | Block-aware ingestion carries chunk -> canonical block -> page/section/bbox provenance to API sources. |
| CI / build | READY | Normal branch CI, retrieval quality and production image build are green on the audited main ref. |
| Release-quality benchmark | IMPLEMENTED / BASELINE PENDING | 330-case controlled corpus and live production-pipeline runner exist; approved baseline is not yet established in-repo. |
| Performance qualification | IMPLEMENTED / EVIDENCE PENDING | SMALL/MEDIUM harness exists; retained live baseline evidence is still required. |
| Release governance | PARTIAL | Integrated qualification workflow exists, but `main` branch protection / required checks are not enforced. |

## What v1.1 materially adds

The v1.1 runtime is no longer only a sophisticated retrieval pipeline. It now contains a controlled learning and promotion loop:

```text
MEASURE
  -> RETRIEVE
  -> GENERATE
  -> VERIFY
  -> LEARN
  -> OFFLINE EVALUATE
  -> SHADOW
  -> CANARY
  -> APPROVE / ROLLBACK
  -> MEASURE AGAIN
```

The learning boundary remains intentionally conservative:

- authoritative source documents are not rewritten by traffic;
- learning may adapt retrieval policy, query memory and semantic associations;
- unapproved learned policy cannot enter production;
- SHADOW cannot affect the user answer;
- CANARY uses bounded cohort routing and measured holdback evidence;
- promotion is blocked when benchmark, performance, shadow or canary evidence is insufficient;
- rollout evidence is privacy-safe and deduplicated.

## Closed code-level defects from the previous audit ledger

The GitHub issue tracker still lists C01-C06 as open, but the audited `main` contains the corresponding remediation. The issues should be reconciled/closed after final CI/release-evidence review so the tracker matches current code.

### C01 / issue #36 — TTL retrieval fence

Current retrieval eligibility applies:

```sql
retention_status = 'ACTIVE'
AND (expires_at IS NULL OR expires_at > clock_timestamp())
```

through the published lifecycle eligibility path. Expired-but-still-ACTIVE content is therefore fenced synchronously rather than relying only on the retention scheduler.

### C02 / issue #37 — non-local security fail-closed

`SecurityStartupValidator` resolves explicit `AKMAI_ENVIRONMENT` / `akmai.environment` and treats any non-local deployment as hardened. Hardened startup rejects disabled security and unauthenticated-local mode.

### C03 / issue #38 — explicit database credentials

The same startup validator requires explicit non-local DB username/password and rejects the predictable `akmai/akmai` defaults outside local development.

### C04 / issue #39 — timeout/resource release

The retrieval executor owns cancellable `FutureTask` instances, enforces `strategy-timeout < request-timeout`, prevents late dependent work when a full resource budget no longer remains, classifies timeout outcomes, and is complemented by JDBC/model transport deadlines and worker-recovery regressions.

### C05 / issue #40 — request byte limit

`RequestBodySizeFilter` reads at most `maxRequestBytes + 1` bytes from the actual request stream and returns HTTP 413 when the cap is exceeded. This covers unknown-length/chunked bodies and avoids relying only on `Content-Length`.

### C06 / issue #41 — re-embedding replica fencing

Re-embedding state now persists `owner_id`, `lease_until` and monotonic `fencing_token`; migration updates are owner/token scoped, preventing a stale replica from mutating a migration after lease takeover.

## Benchmark and release evidence

### Controlled benchmark

`benchmarks/rag-benchmark-v1/materialize_controlled_corpus.py` materializes the versioned controlled dataset used by release engineering:

- 330 labelled queries;
- 255 answerable cases;
- 75 unanswerable cases (22.7%);
- 11 target languages;
- LEGAL / MEDICAL / TECHNICAL domains;
- required query classes and EASY/MEDIUM/HARD difficulty;
- deterministic document and child-chunk truth;
- production `HierarchicalChunker` identity verification before live model execution.

This corpus is intentionally marked `CONTROLLED_SYNTHETIC`. It is valid for regression and release qualification mechanics, but **must not be presented as evidence of real-world legal/medical/technical task accuracy**.

### Integrated release qualification

`.github/workflows/rag-v1.1-release-qualification.yml` requires three independent gates:

1. **quality** — real production ingestion/retrieval/answer path + immutable baseline comparison;
2. **performance** — SMALL and MEDIUM application-level load profiles;
3. **grounding** — RU/KK/EN semantic grounding calibration.

A release is qualified only when all three jobs succeed and the workflow emits `rag-v1.1-qualification.json` with `qualified=true`.

## Remaining release blockers

### R1 — establish the immutable approved quality baseline

The release-quality workflow expects:

`benchmarks/rag-benchmark-v1/baselines/approved.json`

for normal candidate comparison. The baseline must be generated from an explicitly approved live run and committed/versioned before v1.1 can be considered reproducibly qualified.

### R2 — execute and retain a successful integrated qualification run

Run `RAG v1.1 Release Qualification` with `AKMAI_LIVE_OLLAMA_BASE_URL` configured. Retain the quality, performance, grounding and final qualification artifacts against the exact release SHA/tag.

### R3 — enable default-branch governance

`main` is currently unprotected and has no required status checks. Before a formal production release, enable repository rules / branch protection requiring at minimum:

- PR-based changes to `main`;
- `CI` success;
- `Retrieval Quality Gate` success;
- review / force-push restrictions appropriate to the repository;
- release qualification as a required release/tag gate rather than an optional manual convention.

### R4 — create a human-reviewed external-validity corpus

The controlled corpus proves mechanics and regression safety, not external task validity. For enterprise/legal/medical production claims, add a separately versioned human-reviewed corpus with representative documents, real queries, answerability labels and evidence annotations.

### R5 — hardware-specific SLO and capacity sign-off

The current performance harness supports SMALL and MEDIUM qualification, but production SLOs must be derived from measured evidence on the intended CPU/GPU/RAM/storage/Ollama topology. Do not copy GitHub Actions latency directly into production SLOs.

## Production-readiness decision

### Ready now

AkmAI is suitable for:

- local development and integration;
- controlled internal deployment;
- pilot enterprise knowledge assistants;
- architecture/integration work with Auth, FileService and a BFF;
- benchmarked experiments with adaptive retrieval and semantic grounding.

### Conditional

AkmAI can be treated as a production-capable RAG module **after** the exact deployment completes the v1.1 release-qualification workflow and target-hardware SLO sign-off.

### Not yet justified

Do not yet claim:

- externally validated legal/medical accuracy;
- universal multilingual quality across arbitrary corpora;
- a production SLO without target-hardware measurements;
- release-qualified self-optimization without retained quality/performance/grounding artifacts.

## Recommended immediate actions

1. Keep `main` green after PR #59 and close/reconcile issues #36-#41 against the actual remediation commits/tests.
2. Run the release-quality workflow once in `establish_baseline=true` mode and review the candidate report.
3. Commit the approved immutable baseline under `benchmarks/rag-benchmark-v1/baselines/approved.json`.
4. Run the integrated v1.1 qualification workflow and retain all artifacts for the release SHA/tag.
5. Enable branch protection / repository rules for `main`.
6. Build a separately versioned human-reviewed corpus for external quality claims.
7. Run target-hardware performance qualification and derive operational SLOs / alerts from measured p95/p99, throughput and saturation.

## Source-of-truth documents

- `README.md` — product overview and current release state;
- `docs/architecture/rag-self-optimizing-platform-v1.1-technical-spec.md` — v1.1 engineering contract;
- `benchmarks/rag-benchmark-v1/README.md` — benchmark contract and controlled corpus limits;
- `.github/workflows/rag-v1.1-release-qualification.yml` — integrated release gate;
- this document — current post-v1.1 readiness decision.
