# Semantic Domain Corpus v2 — English-first roadmap

Classification: **TARGET / IMPLEMENTATION ROADMAP**  
Branch: `feature/semantic-domain-corpus-v2`  
Initial language: **English (`en`)**  
Migration strategy: **English canonical corpus first, then language surfaces are ported onto stable concept/domain identities**

## 1. Goal

Expand AkmAI's semantic knowledge layer without adding another retrieval lane.

Each existing semantic `domainId` receives **400 new curated multi-word professional phrases**. Existing phrases remain in place and are reviewed separately; they do not count toward the +400 quota.

The English corpus becomes the semantic reference for later Russian, Kazakh and other language surfaces.

Target expansion: **6,400 new English phrases** in total.

## 2. Current implementation status

The quality guardrails are implemented in `feature/semantic-domain-corpus-v2` and finance/banking expansion is in progress.

`finance_banking` status:

- Batch A: +100 accepted into the branch;
- Batch B: +100 accepted into the branch;
- Batch C: +100 accepted into the branch;
- Batch D: pending;
- cumulative finance expansion: **+300 / +400**;
- 75 additions per existing finance subdomain;
- finance concepts: **24 -> 324**;
- total English concepts: **384 -> 684**.

Batch resources:

- `semantic/concepts-en-finance-batch-a-v2.yaml`;
- `semantic/concepts-en-finance-batch-b-v2.yaml`;
- `semantic/concepts-en-finance-batch-c-v2.yaml`.

## 3. Current baseline taxonomy

The existing domain/subdomain taxonomy remains authoritative. Current domain IDs:

1. `finance_banking`
2. `insurance`
3. `energy_utilities`
4. `oil_gas_mining`
5. `manufacturing`
6. `construction_real_estate`
7. `transport_logistics`
8. `agriculture_food`
9. `mathematics_statistics`
10. `physics_astronomy`
11. `chemistry_materials`
12. `biology_genetics`
13. `computer_science_ai`
14. `medicine_pharmacology`
15. `earth_environmental_science`
16. `psychology_sociology`

## 4. Phrase admission standard

A qualifying entry MUST:

- be a real professional/domain phrase rather than synthetic keyword concatenation;
- normally contain 2–6 lexical tokens;
- have a specific useful domain meaning;
- be suitable for semantic retrieval/query expansion;
- be globally unique after canonical normalization;
- not be a trivial morphological duplicate;
- not duplicate an existing alias representing the same concept;
- preserve existing domain/subdomain semantics;
- avoid unstable temporary terminology;
- add measurable retrieval value rather than merely satisfying quota.

Single-word terms, acronyms and abbreviations may be aliases but do not count toward the +400 canonical phrase quota.

## 5. Duplicate and ambiguity policy

Exact and normalized canonical duplicates are forbidden and fail at catalog construction.

Wording-only, morphological, singular/plural and equivalent paraphrase variants belong in the alias/surface layer rather than becoming separate concepts.

Lexically similar phrases are reviewed by `SemanticCorpusNearDuplicateAuditor`. Similarity is advisory only; concepts are merged only when they resolve to essentially the same professional meaning and evidence.

Ambiguous surfaces are represented explicitly through `SemanticAmbiguityRegistry`; ambiguity is never hidden by assigning one shared surface silently to multiple concepts.

Allowed review dispositions:

- `KEEP_BOTH`;
- `MERGE_CANONICAL`;
- `REJECT_DUPLICATE`;
- `REASSIGN`.

## 6. English-first multilingual contract

English v2 may grow independently of existing multilingual v1 surface packs.

The current RU/KK/other packs remain stable against their already published v1 concept identities. New English v2 concepts do not require immediate translation to every supported language. Language transfer begins after the English reference passes consolidation and benchmark gates.

## 7. Delivery stages

### Stage 0 — quality guardrails

Implemented in the branch:

- deterministic normalization;
- global canonical uniqueness;
- near-duplicate review;
- ambiguity registry;
- alias/surface ownership validation;
- English-first versioning contract;
- baseline and batch count tests.

### Stage 1 — finance and regulated business

Target +400 each for:

- `finance_banking`;
- `insurance`.

Finance progress is currently +300 / +400.

### Stage 2 — industrial and infrastructure

Target +400 each for energy/utilities, oil/gas/mining, manufacturing, construction/real estate, transport/logistics and agriculture/food.

### Stage 3 — computing, medicine and life sciences

Target +400 each for computer science/AI, medicine/pharmacology, biology/genetics and chemistry/materials.

### Stage 4 — mathematics, physics, environment and human sciences

Target +400 each for mathematics/statistics, physics/astronomy, earth/environmental science and psychology/sociology.

### Stage 5 — English consolidation

- verify normalized uniqueness globally;
- review near-duplicate candidates;
- remove synthetic or weak entries;
- check subdomain balance;
- run retrieval benchmark;
- freeze approved English reference.

### Stage 6 — language transfer

Port approved concepts/surfaces in this order:

1. Russian (`ru`);
2. Kazakh (`kk`);
3. remaining supported languages.

Translation alone is insufficient; target-language surfaces must use real professional terminology.

## 8. Required validation

CI/test coverage must verify:

- YAML parsing;
- unique domain/subdomain identifiers;
- 2–6 word canonical phrase rule;
- global normalized canonical uniqueness;
- no silent alias ownership collisions;
- ambiguity entries reference real concepts;
- exact per-batch/domain counts;
- deterministic bounded semantic matching;
- retrieval quality does not regress materially.

## 9. Benchmark gates

Each batch is evaluated on representative exact, paraphrase, partial, ambiguous and negative queries.

Track at minimum:

- Recall@k;
- MRR/nDCG where labels exist;
- context precision;
- semantic-lane contribution;
- false-positive expansion rate;
- latency impact;
- expansion count distribution.

Count alone is never a release criterion.

## 10. Definition of done for v2 English

- all 16 domains receive +400 curated phrases;
- 6,400 additions pass normalization and collision checks;
- every batch has near-duplicate review;
- wording-only duplicates are aliases, not separate concepts;
- ambiguous surfaces are explicit;
- benchmark precision/grounding are no worse than baseline and terminology-heavy recall improves measurably;
- English reference is stable enough for RU/KK transfer.

## 11. Immediate next slice

Complete `finance_banking` Batch D (+100), bringing finance to +400 total. Then perform finance consolidation review before moving to `insurance`.
