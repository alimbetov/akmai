# Adaptive Graph Dream — pragmatic v1

## Final scope

The `feature/adaptive-graph-dream` branch intentionally stops at DREAM-5.

```text
DREAM-1..4B
safe, bounded, fenced discovery and shadow candidate evaluation
        ↓
DREAM-5
safe semantic-prior materialization into knowledge_chunk_association
        ↓
STOP / reevaluate from production evidence
```

There is no committed DREAM-6/DREAM-7 implementation plan in this branch.

## DREAM-5 contract

DREAM-5 may materialize only a semantic `CANDIDATE` prior into the existing
`knowledge_chunk_association` graph. It must not create a second online graph,
produce learned evidence, or directly promote relations to WARM/HOT.

Required safety properties:

- static Dream enable AND runtime Dream enable are both required;
- static apply enable AND runtime apply enable are both required;
- PostgreSQL-time lease/fencing is authoritative;
- stale owners cannot mutate the graph;
- source and target lifecycle/TTL eligibility is rechecked inside the apply transaction;
- canonical node locks serialize competing semantic writers;
- both graph directions are written in one transaction;
- semantic degree is bounded by the existing semantic-memory edge limit;
- existing learned counters are never overwritten;
- existing WARM/HOT state is never downgraded by Dream;
- a DECAYED association may return to CANDIDATE only when it has no learned evidence;
- transaction timeout bounds each graph-apply transaction;
- shadow mode (`apply=false`) leaves `knowledge_chunk_association` unchanged.

The semantic value written by DREAM-5 is the conservative reciprocal similarity:

```text
min(forward_similarity, reverse_similarity)
```

Only candidates that are `ACTIVE` in the current Dream run are eligible for
DREAM-5 materialization, and the existing `maxNewEdgesPerChunk` run limit still
applies before graph admission.

## Explicit non-goals

This branch does not implement:

- semantic retirement;
- learned-evidence retirement;
- automatic WARM/HOT promotion;
- ANN parallelism;
- distributed multi-owner sharding;
- adaptive threshold learning;
- Dream-driven online ranking changes;
- replacement/eviction of existing semantic neighbours when degree is full.

If semantic degree is full, DREAM-5 returns a degree-limit result and leaves the
existing graph unchanged.
