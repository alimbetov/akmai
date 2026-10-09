# AkmAI service documentation inventory

This file tracks documentation coverage of the current codebase.

A service/process is considered **documented** only when its contract contains:

- Process;
- Business Rules;
- Positive Cases;
- Negative Cases;
- concrete Tests;
- transaction/concurrency/failure semantics where applicable.

## Status legend

- `TODO` — contract not yet created.
- `DRAFT` — contract exists and is mapped to implementation, but rules/cases/tests are incomplete or the exact documentation/code head has not yet passed the required verification gates.
- `VERIFIED` — contract is mapped to current implementation and concrete positive/negative tests with green exact-head verification.

`IMPLEMENTED` inside an individual contract describes code state only. It is not equivalent to inventory status `VERIFIED`.

## Inventory

| Domain | Service / Process | Contract | Status |
|---|---|---|---|
| Retrieval | Query / retrieval orchestration | [`retrieval-flow.md`](retrieval-flow.md) | DRAFT |
| Retrieval | Retrieval policy / routing | [`retrieval-routing.md`](retrieval-routing.md) | DRAFT |
| Retrieval | Retrieval execution / degradation semantics | [`retrieval-execution.md`](retrieval-execution.md) | DRAFT |
| Retrieval | Fusion / rerank / context selection | [`retrieval-selection.md`](retrieval-selection.md) | DRAFT |
| Retrieval | Retrieval quality / abstention | [`retrieval-flow.md`](retrieval-flow.md) | DRAFT |
| Knowledge ingestion | Document ingestion | [`knowledge-ingestion.md`](knowledge-ingestion.md) | DRAFT |
| Knowledge ingestion | Chunk lifecycle / TTL retention | [`chunk-lifecycle-retention.md`](chunk-lifecycle-retention.md) | DRAFT |
| Embeddings | Re-embedding HA / lifecycle | [`reembedding-ha-lifecycle.md`](reembedding-ha-lifecycle.md) | DRAFT |
| Graph | Adaptive chunk graph mutation | [`adaptive-graph-mutation.md`](adaptive-graph-mutation.md) | DRAFT |
| Graph | Semantic association seeding | [`adaptive-graph-mutation.md`](adaptive-graph-mutation.md) | DRAFT |
| Graph / Dream | Semantic graph prior application | [`adaptive-graph-mutation.md`](adaptive-graph-mutation.md) | DRAFT |
| Graph / Dream | Dream ownership / lease / fencing | [`dream-cycle.md`](dream-cycle.md) | DRAFT |
| Graph / Dream | Dream candidate lifecycle | [`dream-cycle.md`](dream-cycle.md) | DRAFT |
| Graph maintenance | Graph cleanup / maintenance | [`scheduled-jobs.md`](scheduled-jobs.md), [`adaptive-graph-mutation.md`](adaptive-graph-mutation.md) | DRAFT |
| Learning | Query memory / learning events | TBD | TODO |
| Publication | Publication / activation lifecycle | [`publication-lifecycle.md`](publication-lifecycle.md) | DRAFT |
| Repair | Generation reconciliation | [`generation-reconciliation.md`](generation-reconciliation.md) | DRAFT |
| Repair | Physical repair / residual cleanup | [`generation-reconciliation.md`](generation-reconciliation.md) | DRAFT |
| Operations | Scheduled/background jobs | [`scheduled-jobs.md`](scheduled-jobs.md) | DRAFT |
| Operations | Runtime feature flags / safety gates | [`runtime-safety-flags.md`](runtime-safety-flags.md) | DRAFT |

## Inventory maintenance rule

When a new service or independently meaningful business process is introduced, it must be added here in the same PR. A PR must not mark an item `VERIFIED` unless every referenced rule and case is backed by current implementation and concrete tests with green verification on the exact same head SHA.

The inventory is a current-state index, not a historical backlog. Historical audit documents may retain older statuses when they are explicitly classified as snapshots, but this file must reflect the current repository state.