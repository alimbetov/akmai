# Quality Stable Baseline — Transaction Boundary Audit

## Scope

Audit of post-DREAM-5 mutation paths with emphasis on keeping network/model work outside database transactions, bounded lock scope, fencing, and transaction timeout behavior.

## Findings

### Re-embedding

`ReembeddingService.stageCandidate(...)` performs projection reads, cloning, lease renewal, embedding generation and vector assembly before entering the persistence transaction. The subsequent transaction contains only ownership revalidation and database writes. This is the desired boundary: model latency does not hold PostgreSQL locks or a pooled connection for the duration of inference.

### DREAM-5 semantic prior apply

`SemanticGraphPriorWriter.applyCandidate(...)` creates a bounded transaction with the configured Dream transaction timeout. Inside it the order is:

1. re-check runtime apply gate;
2. validate current lease/fencing authority;
3. lock eligible lifecycle rows canonically;
4. acquire canonical transaction-scoped advisory node locks;
5. inspect pair/degree state;
6. apply fenced semantic mutation.

No ANN/model call runs inside this transaction.

### Semantic ingestion seeding

`SemanticAssociationSeedRepository.seedSymmetric(...)` receives an already-computed similarity and performs only lifecycle locks, node locks, a bounded state probe, and symmetric graph persistence inside the transaction. ANN/model work is outside this repository transaction.

### Learned reinforcement

`AdaptiveChunkGraphRepository.reinforceSymmetricBatch(...)` performs validation before entering the transaction. The transaction contains lifecycle locks, node locks, and batched DB upserts only.

### Policy registry

Policy activation/rejection transactions contain row locking and registry state transitions only; no remote/model calls were found in their transaction callbacks.

## Required invariants

- No embedding/LLM/HTTP call while a graph/lifecycle DB transaction is open.
- Lifecycle rows are locked before graph-node advisory locks.
- Graph node locks use one namespace and canonical ordering.
- Dream ownership is checked inside the transaction and remains repeated inline in race-sensitive mutation SQL.
- Long-running Dream orchestration is never wrapped in one transaction.
- Transaction timeout conversion is centralized through `TransactionTimeouts`.

## Result

No transaction-boundary defect requiring a semantic change was identified in the audited paths. The hardening change is architectural deduplication and tighter/consistent primitives, not a widening of transactional scope.
