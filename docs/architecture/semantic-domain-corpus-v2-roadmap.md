# Semantic Domain Corpus v2 — English-first roadmap

Classification: **TARGET / IMPLEMENTATION ROADMAP**  
Branch: `feature/semantic-domain-corpus-v2`  
Initial language: **English (`en`)**  
Migration strategy: **English canonical corpus first, then language surfaces are ported onto stable concept/domain identities**

## 1. Goal

Expand AkmAI's semantic knowledge layer without adding another retrieval lane.

Each existing semantic `domainId` receives **400 new curated multi-word professional phrases**. Existing baseline phrases remain in place and do not count toward the +400 quota.

The English corpus becomes the semantic reference for later Russian, Kazakh and other language surfaces.

Target expansion: **6,400 new English phrases**, producing **6,784 English concepts** including the 384-concept baseline.

## 2. Current implementation status

The English expansion is implemented through Batch D for all sixteen domains.

Current target state on `feature/semantic-domain-corpus-v2`:

- 16 domains × 424 concepts = **6,784 concepts**;
- each domain has 24 baseline + 400 v2 additions;
- 64 subdomains × 106 concepts;
- each subdomain has 6 baseline + 100 v2 additions;
- Stages 1, 2, 3 and 4 are implemented;
- no Batch E is planned.

The state remains **provisional until final CI, retrieval-quality, storage benchmark and production-image gates are green on the same final head**.

## 3. Authoritative taxonomy

The existing domain/subdomain taxonomy remains authoritative. Domain IDs:

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
- contain 2–6 lexical tokens under production normalization;
- have a specific useful domain meaning;
- be suitable for semantic retrieval/query expansion;
- be globally unique after canonical normalization;
- not be a trivial morphological or wording-only duplicate;
- not duplicate an existing alias representing the same concept;
- preserve existing domain/subdomain semantics;
- avoid unstable temporary terminology;
- add retrieval value rather than merely satisfying quota.

Single-word terms, acronyms and abbreviations may be aliases but do not count toward the +400 canonical phrase quota.

## 5. Duplicate and ambiguity policy

Exact and normalized canonical duplicates are forbidden and fail at catalog construction.

Wording-only, morphological, singular/plural and equivalent paraphrase variants belong in the alias/surface layer rather than becoming separate concepts.

Lexically similar phrases are reviewed by `SemanticCorpusNearDuplicateAuditor`. Similarity is advisory only; no automatic merge or delete is performed.

Ambiguous surfaces are represented through `SemanticAmbiguityRegistry` as corpus-quality metadata. The registry does **not** imply query-time contextual disambiguation in the current `SemanticConceptMatcher`.

## 6. English-first multilingual contract

English v2 may grow independently of existing multilingual v1 surface packs.

The current RU/KK/other packs remain stable against their already published v1 concept identities. New English v2 concepts do not require immediate translation to every supported language. Language transfer starts only after the English reference passes consolidation and benchmark gates.

## 7. Delivery stages

### Stage 0 — quality guardrails

Implemented:

- deterministic normalization;
- global canonical uniqueness;
- near-duplicate auditing;
- ambiguity registry;
- alias/surface ownership validation;
- English-first versioning contract;
- baseline, batch and count tests.

### Stage 1 — finance and regulated business

Complete at +400 each for `finance_banking` and `insurance`.

### Stage 2 — industrial and infrastructure

Complete at +400 each for energy/utilities, oil/gas/mining, manufacturing, construction/real estate, transport/logistics and agriculture/food.

### Stage 3 — computing, medicine and life sciences

Complete at +400 each for computer science/AI, medicine/pharmacology, biology/genetics and chemistry/materials.

### Stage 4 — mathematics, physics, environment and human sciences

Implemented at +400 each for mathematics/statistics, physics/astronomy, earth/environmental science and psychology/sociology. Final acceptance is gate-driven.

### Stage 5 — English consolidation

Next phase after final Stage 4 gates are green:

- verify normalized uniqueness globally;
- disposition remaining near-duplicate candidates;
- remove or replace weak/synthetic entries one-for-one where necessary;
- verify subdomain balance and canonical stability;
- capture retrieval benchmark evidence;
- freeze the approved English reference.

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
- exactly 6,784 concepts;
- exactly 424 concepts per domain;
- exactly 106 concepts per subdomain;
- deterministic bounded semantic matching;
- retrieval quality does not regress materially.

## 9. Benchmark gates

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
- total catalog size is 6,784;
- all 64 subdomains contain 106 concepts;
- wording-only duplicates are aliases, not separate concepts;
- ambiguous surfaces are explicit where required;
- final CI and benchmark gates pass on the same head;
- benchmark precision/grounding are no worse than baseline and terminology-heavy recall improves measurably;
- English reference is stable enough for RU/KK transfer.

## 11. Immediate next slice

Do not expand quota further. Complete final gate remediation for the 6,784-concept head, then perform **Stage 5 English consolidation** and freeze the English canonical reference before RU → KK transfer.
