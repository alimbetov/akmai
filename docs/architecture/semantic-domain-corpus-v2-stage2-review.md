# Semantic Domain Corpus v2 — Stage 2 review

Status: TARGET / review record for `feature/semantic-domain-corpus-v2`.

## Scope

Stage 2 is complete for the six operational domains. Each now contains four balanced +100 rounds:

- `energy_utilities`: Batch A/B/C/D = +400, 424 total;
- `oil_gas_mining`: Batch A/B/C/D = +400, 424 total;
- `manufacturing`: Batch A/B/C/D = +400, 424 total;
- `construction_real_estate`: Batch A/B/C/D = +400, 424 total;
- `transport_logistics`: Batch A/B/C/D = +400, 424 total;
- `agriculture_food`: Batch A/B/C/D = +400, 424 total.

Reference domains are also complete:

- `finance_banking`: +400, 424 total;
- `insurance`: +400, 424 total.

The English canonical corpus represented by this branch now targets 3,584 concepts: 384 baseline concepts plus 3,200 additions.

## Review method

Every supplement is loaded through the production `EnglishSemanticConceptCatalog` normalization and must satisfy:

1. 2–6 canonical words;
2. globally unique canonical phrase after normalization;
3. stable domain/subdomain-derived ID;
4. valid domain/subdomain ownership;
5. English preferred/alias surface ownership rules;
6. ambiguity registry referential integrity.

`SemanticCorpusNearDuplicateAuditor` remains advisory. Lexical similarity produces review candidates but never performs automatic merges.

## Exact-duplicate debt discovered during Stage 2

During Batch C preparation, review of already committed Stage 2 A/B files found exact canonical repeats. Because `EnglishSemanticConceptCatalog` intentionally rejects normalized duplicates, these were treated as blocking corpus defects rather than tolerated aliases.

The uniqueness gate was not weakened. Repeated entries were replaced by distinct professional concepts while preserving the 25-per-subdomain batch contract.

Representative corrections included:

- Energy/Utilities: `heat rate curve` → `incremental heat rate`, repeated market/grid/storage surfaces replaced with distinct operational concepts;
- Oil/Gas/Mining: `pipeline capacity analysis` → `pipeline throughput capacity`, repeated drilling/refinery/mining surfaces replaced with independent concepts;
- Manufacturing/Transport: cross-domain repeats such as generic planning/ATP/CTP surfaces were made domain-specific;
- Construction/RE: repeated lease-event wording was replaced;
- Transport/Logistics: repeated warehouse/fleet/supply-chain surfaces were replaced, including `warehouse slotting optimization` → `dynamic slotting policy`;
- Agriculture/Food: repeated crop/livestock/process/food-safety surfaces were replaced, including `food fraud vulnerability` → `authenticity risk assessment`.

This defect class is now part of the Stage 2 quality record: sequential batch review must include exact cross-batch comparison before a batch is considered admissible.

## Batch D coverage

Batch D completes the target without adding synonym padding. Representative additions include:

- Energy: `probabilistic load flow`, `frequency nadir assessment`, `capacity performance obligation`, `renewable adequacy contribution`;
- Oil/gas/mining: `reservoir sweep efficiency`, `high consequence area`, `refinery loss accounting`, `conditional simulation model`;
- Manufacturing: `production stability index`, `quality risk prioritization`, `lifecycle maintenance cost`, `valve stiction detection`;
- Construction/real estate: `vibration serviceability assessment`, `rental reversion analysis`, `lifecycle replacement reserve`, `completion probability estimate`;
- Transport/logistics: `spot rate volatility`, `fulfillment cost per order`, `fleet lifecycle economics`, `total landed cost`;
- Agriculture/food: `crop water productivity`, `feed cost per gain`, `production mass balance`, `traceability response time`.

Each Batch D file contains exactly 25 concepts for each of the domain's four existing subdomains.

## Explicit ambiguity registrations

Existing explicit generic-surface ambiguity registrations remain in force, including:

- `preventive maintenance`;
- `capacity planning`;
- `settlement reconciliation`;
- `reserve requirement`;
- `risk appetite`;
- `asset management`;
- `risk assessment`;
- `risk model`;
- `decision making`.

New Batch D canonical forms should be added to the ambiguity registry only when the same generic user surface can legitimately resolve to multiple distinct concepts. Similar terminology alone is not sufficient.

## Release gates

Completed Stage 2 is admissible only when the current branch head passes:

1. canonical normalized uniqueness;
2. multi-word phrase constraints;
3. expected 3,584 total concepts;
4. 424 concepts for each of the eight expanded domains and 106 per subdomain;
5. ambiguity registry referential integrity;
6. English surface ownership checks;
7. near-duplicate review with no unresolved merge-class candidates;
8. repository CI;
9. Retrieval Quality Gate;
10. Retrieval Storage Final Benchmark;
11. Production Image Build.

Until those gates complete successfully, the 3,584-concept state is implemented but provisional.

## Next expansion

After Stage 2 is green and consolidated, stop deepening these eight domains. The next English-first stage should expand the remaining science/technology domains rather than add a fifth batch here. Recommended next group: `computer_science_ai`, `medicine_pharmacology`, `biology_genetics`, and `chemistry_materials`, using the same +100 batch and cross-domain review discipline.
