# Disaster recovery and canonical rebuild rehearsal

## Recovery contract

AkmAI deliberately does not guarantee restoration of an individually purged
retrieval generation.

- Per-generation retrieval payload RPO: not guaranteed.
- PITR: whole PostgreSQL database only.
- Canonical source: the only supported source for regeneration after retrieval
  data loss.
- Retirement tombstones are proof of purge and are not a source for payload
  restoration.

The canonical source must therefore have an independent durability and backup
policy at least as strong as the production recovery objective.

## Two recovery paths

### Whole-database restore

Use PostgreSQL PITR when the required recovery point exists and restoring the
whole AkmAI database is acceptable.

1. Quiesce writers and RAG traffic.
2. Restore PostgreSQL to an isolated recovery environment.
3. Start AkmAI with the exact application release and embedding configuration
   that matches the restored database.
4. Wait for Liquibase and readiness checks.
5. Run `docs/operations/dr-post-rebuild-verification.sql`.
6. Run deterministic multilingual retrieval quality.
7. Run a sampled canonical-document comparison.
8. Promote only when every verification query returns zero inconsistencies.

### Canonical rebuild

Use this path when the retrieval database cannot be trusted or no usable PITR
point exists.

1. Create a new empty PostgreSQL database; never rebuild in place over the
   damaged database.
2. Start the exact AkmAI release so Liquibase provisions the greenfield schema,
   language leaves, access partitions and embedding runtime.
3. Provision every access level required by the canonical inventory.
4. Configure the intended active embedding profile.
5. Replay canonical documents through the normal ingestion API/pipeline. Do
   not bulk-load vector/projection tables directly.
6. Keep production read traffic disabled while replay is incomplete.
7. Allow generation reconciliation to reach a stable zero backlog.
8. Run `docs/operations/dr-post-rebuild-verification.sql`.
9. Run deterministic retrieval quality and the live Ollama quality gate.
10. Compare canonical inventory counts against READY/ACTIVE document counts.
11. Sample legal and medical documents in every supported language and compare
    expected citations/chunks.
12. Cut traffic only after the acceptance gates below pass.

## Acceptance gates

A rebuild is acceptable only when all conditions are true:

- readiness is UP, including database, pgvector, embedding profile and Ollama;
- all five inconsistency queries in
  `dr-post-rebuild-verification.sql` return zero rows;
- reconciliation backlog is zero and stays zero for two consecutive sampling
  intervals;
- retention verification is not accumulating;
- deterministic retrieval quality is green for all target languages;
- live embedding quality is green for all target languages;
- no executor rejections or retrieval timeouts occur during the validation
  window;
- canonical inventory and READY/ACTIVE inventory agree;
- the active embedding profile fingerprint matches the intended release.

## Rehearsal cadence

Run a non-production rebuild rehearsal at least quarterly and after changes to:

- canonical-source storage or export format;
- database schema or partition topology;
- embedding profile migration;
- retention/tombstone semantics;
- ingestion/idempotency semantics.

Record start/end time, canonical document count, generated chunk/vector counts,
peak replay throughput, final verification output, quality results and any
manual interventions. The measured recovery time is the evidence for the
operational RTO; do not infer RTO from schema size alone.

## Safety

Never run destructive recovery commands against the production database during
a rehearsal. Use a separately named database/cluster and isolated credentials.
The damaged/restored source must be kept read-only until the new environment
passes acceptance.
