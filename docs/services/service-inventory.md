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
- `DRAFT` — contract exists but rules/cases/tests are incomplete or not yet verified by CI.
- `VERIFIED` — contract is mapped to current implementation and concrete tests with green verification.

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
| Embeddings | Embedding / re-embedding | TBD | TODO |
| Graph | Adaptive chunk graph mutation | TBD | TODO |
| Graph | Semantic association seeding | TBD | TODO |
| Graph / Dream | Semantic graph prior application | TBD | TODO |
| Graph / Dream | Dream ownership / lease / fencing | TBD | TODO |
| Graph / Dream | Dream candidate lifecycle | TBD | TODO |
| Graph maintenance | Graph cleanup / maintenance | TBD | TODO |
| Learning | Query memory / learning events | TBD | TODO |
| Publication | Publication / activation lifecycle | [`publication-lifecycle.md`](publication-lifecycle.md) | DRAFT |
| Repair | Generation reconciliation | [`generation-reconciliation.md`](generation-reconciliation.md) | DRAFT |
| Repair | Physical repair / residual cleanup | [`generation-reconciliation.md`](generation-reconciliation.md) | DRAFT |
| Operations | Scheduled/background jobs | TBD | TODO |
| Operations | Runtime feature flags / safety gates | TBD | TODO |

## Inventory maintenance rule

When a new service or independently meaningful business process is introduced, it must be added here in the same PR. A PR must not mark an item `VERIFIED` unless every referenced rule and case is backed by current implementation and concrete tests with green verification.

The table above is intentionally the starting inventory, not a claim of completeness. During the codebase pass, split or merge rows to match actual service/process boundaries discovered in implementation.
