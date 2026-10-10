# Semantic Domain Corpus v2 — Stage 2 review

Status: TARGET / review record for `feature/semantic-domain-corpus-v2`.

## Scope

This review covers the first balanced expansion beyond finance:

- `insurance`: Batch D, completing +400;
- `energy_utilities`: Batch A +100;
- `oil_gas_mining`: Batch A +100;
- `manufacturing`: Batch A +100;
- `construction_real_estate`: Batch A +100;
- `transport_logistics`: Batch A +100;
- `agriculture_food`: Batch A +100.

Together with the already completed finance and insurance batches, the English canonical corpus target represented by this branch is 1,784 concepts.

## Review method

The stage uses the same production normalization as `EnglishSemanticConceptCatalog` and the advisory token-overlap policy from `SemanticCorpusNearDuplicateAuditor`.

For the 700 phrases introduced in this stage slice, an overlap review at the existing 0.66 threshold produced 165 lexical candidates, including 59 cross-domain candidates. No stage-local cross-domain pair exceeded the threshold score of 0.66. These candidates are review input only; lexical similarity is not sufficient evidence for an automatic merge.

Every candidate is assigned one of the existing review dispositions:

- `KEEP_BOTH` — lexically close but materially different evidence/intent;
- `MERGE_CANONICAL` — the wording expresses the same semantic concept;
- `REJECT_DUPLICATE` — redundant canonical entry;
- `REASSIGN` — correct concept but wrong domain/subdomain;
- `REGISTER_AMBIGUITY` — a generic surface can legitimately resolve to multiple concepts depending on context.

## Consolidation changes

The review found several cases that were too close to remain independent canonical concepts. They were replaced rather than retained to satisfy quota.

### Insurance

- `errors omissions coverage` overlapped materially with existing `professional liability coverage`; it was replaced by `media liability coverage`.
- `boiler machinery coverage` was a legacy synonym for equipment breakdown coverage; it was replaced by `ordinance law coverage`.
- `replacement cost valuation` overlapped materially with existing replacement-cost concepts; it was replaced by `actual loss sustained`.
- `underwriting referral governance` overlapped with the existing underwriting referral process; it was replaced by `underwriter workload allocation`.
- `building replacement valuation` duplicated the replacement-cost semantic cluster; it was replaced by `property exposure geocoding`.
- `professional indemnity limit` competed with professional-liability terminology; it was replaced by `fiduciary liability exposure`.
- `environmental impairment liability` overlapped with environmental-liability coverage; it was replaced by `environmental cleanup expense`.
- `machinery breakdown risk` overlapped with equipment-breakdown risk; it was replaced by `pressure vessel exposure`.

### Construction / real estate

- `planned preventive maintenance` overlapped with the existing `preventive facility maintenance`; it was replaced by `facility maintenance prioritization`.
- `market vacancy rate` was too close to `vacancy rate analysis` for the same batch; it was replaced by `lease expiry profile`.

### Manufacturing

- `root cause failure analysis` overlapped materially with the baseline `root cause analysis`; it was replaced by `bad actor equipment analysis`.

## Explicit ambiguity registrations

The ambiguity registry now records generic surfaces whose meaning is legitimately domain-dependent:

- `preventive maintenance` — manufacturing equipment, building facilities, or vehicle fleets;
- `capacity planning` — power transmission, manufacturing capacity requirements, warehouse distribution, or supplier capacity;
- `settlement reconciliation` — banking payments or electricity-market settlement;
- `reserve requirement` — banking liquidity reserves or generation reliability reserves;
- `risk appetite` — banking enterprise risk or insurance underwriting appetite;
- `asset management` — property/facilities or grid/substation assets.

Existing ambiguity entries for `risk assessment`, `risk model`, and `decision making` remain valid.

## Representative KEEP_BOTH decisions

The following examples are lexically close but should remain separate canonical concepts because they imply different evidence and operational meaning:

- `fuel consumption forecast` vs `fuel consumption monitoring`;
- `reinsurance cost allocation` vs `transport cost allocation`;
- `product recall coverage` vs `product recall procedure`;
- `refinery emissions monitoring` vs `fleet emissions monitoring`;
- `yield loss analysis` vs `process loss analysis`;
- `turnaround scope planning` vs `turnaround maintenance planning`;
- `supplier corrective action` vs food-safety `corrective action procedure`;
- `space utilization analysis` vs `warehouse space utilization`.

These are not aliases: a domain expert would expect different supporting documents, measurements, controls, and downstream retrieval evidence.

## Release gates

Stage 2 is admissible only when the current branch head passes:

1. canonical normalized uniqueness;
2. multi-word phrase constraints;
3. expected per-domain and per-subdomain counts;
4. ambiguity registry referential integrity;
5. English surface ownership checks;
6. repository CI;
7. Retrieval Quality Gate;
8. Retrieval Storage Final Benchmark;
9. Production Image Build.

Until those gates complete successfully, the 1,784-concept state is implemented but provisional.

## Next expansion

After Stage 2 is green, continue with the second +100 batch for the six Stage 2 domains before moving to the science/technology domain group. This keeps semantic coverage balanced and prevents finance/insurance from dominating retrieval vocabulary.
