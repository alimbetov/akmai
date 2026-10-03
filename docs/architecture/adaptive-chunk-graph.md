# Adaptive Chunk Graph

## Status

Target architecture for a self-improving retrieval-memory layer in AkmAI.

This design extends the current hybrid retrieval pipeline without replacing
vector search, lexical search, exact identifiers, or the authoritative
cross-reference graph.

## Problem

AkmAI already finds candidate chunks through vector, lexical, identifier and
reference retrieval. Some chunks repeatedly prove useful together for answering
similar questions, but that retrieval experience is discarded after each
request.

The Adaptive Chunk Graph persists only high-quality, bounded evidence that two
published chunks are useful together. On later requests, strong initial hits can
expand to previously useful neighbours before reranking and context budgeting.

The graph is retrieval memory, not source-of-truth knowledge.

## Non-negotiable invariants

### G1 — ACL is the security boundary

Every graph read and write is scoped by a positive `access_level`.

There is no repository method equivalent to:

```text
findRelated(chunkId)
```

The API always requires allowed access levels and a generation-aware source
identity.

### G2 — No cross-ACL learned edge

A learned association exists inside exactly one access level:

```text
source.access_level == target.access_level == edge.access_level
```

A request that can see multiple ACLs searches only those ACL partitions and
merges the bounded results.

ACL is a pre-routing constraint, never a post-filter.

### G3 — Generation-aware node identity

A graph node is:

```text
(access_level, document_id, generation, chunk_id)
```

A new document generation creates new graph nodes. Learned links from an old
generation never silently migrate to new content.

### G4 — Learned edges are lower authority

Existing exact identifiers and explicit document references remain authoritative.

Suggested ordering:

```text
Tier 0  exact identifier / explicit reference
Tier 1  reserved for future verified domain evidence
Tier 2  vector / lexical fused evidence
Tier 3  structural adjacency
Tier 4  learned association
```

A frequently observed learned edge cannot outrank an explicit reference merely
because it is popular.

### G5 — Canonical target revalidation

The association graph never returns text directly. A graph neighbour is
re-resolved through the ACTIVE/PUBLISHED search projection under the current ACL
before it can become a `RetrievalHit`.

### G6 — Bounded growth

The graph must not approach O(N²).

Each source node has bounded candidate and active degree. Weak/stale relations
decay and are pruned.

### G7 — Behaviour never rewrites authoritative graphs

The existing `knowledge_reference_edge` remains an authoritative representation
of references extracted from documents.

User traffic may create learned associations, but it may not modify explicit
references or automatically rewrite the ontology.

## Three complementary graphs

```text
                         AkmAI knowledge relations
                                  |
              +-------------------+--------------------+
              |                   |                    |
              v                   v                    v
       Reference Graph       Ontology Graph      Association Graph
       document facts        semantic model      retrieval memory
              |                   |                    |
       Art. 5 -> Art. 8    dosage -> medicine     chunk A <-> B
```

### Reference Graph

Existing AkmAI subsystem. Represents explicit document references and structural
anchors. It is not learned from user behaviour.

### Ontology Graph

Versioned semantic structure containing concepts, sectors, facets and semantic
relations. It is curated/data-driven, not reinforced directly by request volume.

### Adaptive Association Graph

Probabilistic, decaying, bounded chunk-to-chunk associations learned from
successful retrieval usage.

## Integration with the current request path

Current path:

```text
QueryChunker
 -> RetrievalPlanner
 -> Vector / Lexical / Identifier / Reference
 -> ResultFusion
 -> Reranker
 -> KnowledgeExpansion
 -> ContextBudget
 -> AnswerGeneration
 -> CitationValidator
```

Target path:

```text
QueryChunker
 -> SemanticQueryAnalyzer
 -> RetrievalPlanner
 -> Vector / Lexical / Identifier / Reference
 -> ResultFusion
 -> Reranker
 -> KnowledgeExpansion
      + structural neighbours
      + adaptive graph neighbours
 -> ContextBudget
 -> AnswerGeneration
 -> CitationValidator
 -> AssociationLearningRecorder
```

Association learning happens after high-quality evidence is available. Raw
retrieval co-occurrence is not sufficient to strengthen an edge.

## Learning signals

Signals are ordered by evidence quality.

```text
co-retrieved only                 no positive reinforcement in v1
both survived reranking           candidate evidence only
both entered bounded context      weak positive evidence
both were cited                   strong positive evidence
explicit document reference       belongs to Reference Graph, not learned graph
```

Version 1 should use positive evidence plus time decay. Absence of citation is
not treated as a strong negative because citation generation itself is imperfect.

### Query diversity

One repeated query must not indefinitely amplify an edge.

A privacy-preserving query fingerprint can be generated from the normalized
semantic query using a server-side keyed digest. The raw question is not required
for edge reinforcement.

Diversity measures should be bounded and should never contain user identity.

## Association lifecycle

```text
DISCOVERED
    |
    v
CANDIDATE
    |
    | minimum independent support
    | minimum confidence
    v
ACTIVE
    |
    | no reinforcement + decay
    v
DECAYED
    |
    v
PURGED
```

Only ACTIVE edges participate in normal online expansion.

Candidate and decayed edges have shorter retention and are periodically compacted.

## Bounded degree

Suggested configurable limits, to be established by benchmark rather than
hard-coded as permanent truth:

```text
max candidate neighbours per source
max active neighbours per source
max graph neighbours fetched per seed
max graph neighbours admitted per request
max learning pairs emitted per request
```

The maintenance job keeps only the highest-value edges for each source node.

This changes graph growth from unbounded pair creation toward approximately:

```text
O(active_chunk_count * max_active_degree)
```

## Edge scoring

Do not use an ever-growing integer counter as the retrieval score.

Conceptually:

```text
support      = saturating_function(successful_independent_observations)
freshness    = decay(time_since_last_reinforcement)
diversity    = bounded_function(distinct_query_fingerprints)
quality      = evidence_quality(citation, context, reranker)
edge_weight  = clamp(support * freshness * diversity * quality, 0, 1)
```

Exact constants belong in configuration and are calibrated by retrieval
benchmarks.

Frequency changes evidence confidence, not factual truth.

## Candidate scoring at query time

A graph edge is not accepted solely by its historic weight.

```text
candidate_score =
    seed_retrieval_score
  * edge_weight
  * query_ontology_compatibility
  * lifecycle_validity
```

The first implementation should use one-hop expansion only.

If a future two-hop mode is introduced, every hop must apply multiplicative
decay and a strict threshold.

## Multiple paths

If several seed chunks reach the same candidate, use the strongest path as the
primary score:

```text
candidate =
max(
    seedA_score * edge(A,X),
    seedB_score * edge(B,X),
    seedC_score * edge(C,X)
)
```

Do not sum arbitrary paths. Summation creates a popularity/hub bias.

A small capped multi-path confidence bonus may be evaluated later.

## Storage design

### Why not partition by domain

Sector/domain/facet similarity is multi-valued and changes with ontology
versions. A chunk may be simultaneously banking, cybersecurity and regulatory.

Domain proximity is a ranking feature, not a physical storage boundary.

### ACL-first partitioning

The association table follows the existing AkmAI security architecture:

```text
knowledge_chunk_association
  PARTITION BY LIST(access_level)

    al_1
      PARTITION BY HASH(source identity)
        h00
        h01
        ...
        h31

    al_2
      PARTITION BY HASH(source identity)
        h00
        ...
        h31
```

The number of hash buckets is fixed per ACL partition and selected by benchmark.
An initial 16 or 32 buckets is reasonable for testing; do not jump to hundreds
without measured need.

Primary online lookup:

```sql
SELECT ...
FROM knowledge_chunk_association
WHERE access_level = ?
  AND source_document_id = ?
  AND source_generation = ?
  AND source_chunk_id = ?
  AND state = 'ACTIVE'
  AND weight >= ?
ORDER BY weight DESC
LIMIT ?;
```

This allows ACL partition pruning, hash subpartition pruning and a bounded
top-N index lookup.

### Directed physical adjacency

Learned co-usage is logically symmetric:

```text
A <-> B
```

For fast adjacency reads it is physically projected as:

```text
A -> B
B -> A
```

Both rows are updated atomically by the repository. This avoids
`source = ? OR target = ?` scans and allows a lookup to touch one source hash
bucket.

Explicit directional document references remain in the separate Reference Graph.

### Proposed columns

```text
access_level

source_document_id
source_generation
source_chunk_id

target_document_id
target_generation
target_chunk_id

association_type
state

weight
support_count
context_count
citation_count
distinct_query_support

first_seen_at
last_seen_at
last_reinforced_at

graph_version
```

Association types for the first implementation:

```text
CO_CONTEXT
CO_CITED
```

Do not duplicate explicit document-reference types here.

### Primary read index

Inside each leaf partition:

```text
(source_document_id,
 source_generation,
 source_chunk_id,
 state,
 weight DESC)
```

Target identity indexes are useful for lifecycle cleanup and diagnostics.

A partial hot index for ACTIVE edges above the configured minimum weight may be
added only after benchmark evidence.

## Retention and repair

The association graph is HOT generation payload.

A retired generation must remove learned associations in addition to the existing
payload:

```text
INSERT retirement tombstone
 -> DELETE vectors
 -> DELETE search projections
 -> DELETE association edges
 -> DELETE reference edges/targets
 -> DELETE identifiers
 -> DELETE vector manifest
 -> verify zero residual payload
 -> finalize generation state
```

Because learned associations are physically symmetric, deleting all source rows
for every chunk in the retired generation removes both logical directions.

`GenerationRepairService` and residual-count verification must include the
association payload before the feature is considered production-safe.

## Graph maintenance

A scheduled bounded maintenance component is required.

Responsibilities:

```text
recompute decayed weights
activate eligible candidates
demote stale ACTIVE edges
purge expired CANDIDATE/DECAYED edges
enforce max degree per source
collect graph-health metrics
```

Maintenance runs in bounded batches and uses the same ACL/generation lifecycle
invariants as other AkmAI workers.

## Hub control

A globally popular chunk must not become a universal graph attractor.

Controls:

- strongest-path scoring rather than path summation;
- max active degree per node;
- ontology compatibility at query time;
- minimum independent query diversity;
- saturating support functions;
- age decay;
- per-request expansion quota.

## Observability

Minimum metrics:

```text
adaptive_graph.learning.events
adaptive_graph.edges.candidate
adaptive_graph.edges.active
adaptive_graph.edges.decayed
adaptive_graph.edge.activation
adaptive_graph.edge.pruned
adaptive_graph.lookup.latency
adaptive_graph.lookup.candidates
adaptive_graph.expansion.accepted
adaptive_graph.expansion.cited
adaptive_graph.expansion.lift
adaptive_graph.maintenance.duration
adaptive_graph.maintenance.backlog
```

Quality dashboards should track whether graph-expanded chunks improve citations
and answer quality rather than merely increasing context size.

## Safety against self-reinforcing retrieval

The graph must not learn from its own presence without independent evidence.

A graph-expanded candidate receives reinforcement only if it survives downstream
quality gates, preferably citation, and the learning event records its origin.

The system must be able to distinguish:

```text
origin = VECTOR
origin = LEXICAL
origin = REFERENCE
origin = ADAPTIVE_GRAPH
```

This makes self-reinforcement measurable and permits stricter activation rules
for graph-originated candidates.

## Versioning

Every edge carries a graph version.

Ontology-derived compatibility is evaluated against an explicit ontology version.

Changes are classified:

```text
QUERY_ONLY
REWEIGHT_REQUIRED
REANNOTATION_REQUIRED
GRAPH_REBUILD_REQUIRED
```

A semantic-library update does not automatically require graph destruction.

## Measurement-driven calibration

Adaptive graph limits are calibrated rather than permanently guessed. The gated process is:

    measure -> calibrate -> replay/load validate -> approve -> canary

Runtime traffic never rewrites its own degree, activation, decay, hash-bucket or maintenance limits.

Before learned graph data exists, explicit reference degree is only a cold-start structural prior. Once shadow learning is available, limits are selected from adaptive-graph telemetry and retrieval-quality evidence.

See adaptive-chunk-graph-calibration.md.

## Initial implementation boundary

Version 1 intentionally does not include:

- multi-hop traversal beyond one hop;
- cross-ACL edges;
- raw user-question persistence;
- automatic ontology mutation from traffic;
- graph database dependency;
- graph partitioning by domain/language/concept;
- graph-only retrieval without strong initial seeds.

PostgreSQL remains sufficient for the first implementation because the online
operation is a bounded adjacency lookup, not arbitrary graph analytics.
