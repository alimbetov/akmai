# Semantic Domain Corpus v2 — English-first roadmap

Classification: **TARGET / IMPLEMENTATION ROADMAP**  
Branch: `feature/semantic-domain-corpus-v2`  
Initial language: **English (`en`)**  
Migration strategy: **English canonical corpus first, then language surfaces are ported onto stable concept/domain identities**

## 1. Goal

Expand AkmAI's semantic knowledge layer without adding another retrieval lane.

The first release establishes a substantially deeper English professional phrase corpus. Each existing semantic `domainId` receives **100 new curated multi-word professional phrases**. Existing phrases remain in place and are reviewed separately; they do not count toward the +100 quota.

The English corpus becomes the semantic reference for later Russian, Kazakh and other language surfaces.

This initiative must improve semantic recall and cross-language portability while preserving precision, bounded query expansion and current retrieval architecture.

## 2. Current baseline

`src/main/resources/semantic/concepts-en-v1.yaml` already defines a domain/subdomain taxonomy and a small curated phrase set. The existing taxonomy is authoritative for v2; this work must not introduce a second incompatible domain classification.

Current domain IDs:

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

Target expansion: **1,600 new English phrases** in total.

## 3. What counts toward the 100-phrase quota

A qualifying entry MUST:

- be a real professional/domain phrase rather than synthetic keyword concatenation;
- normally contain 2–6 lexical tokens;
- have a specific, useful domain meaning;
- be suitable for semantic retrieval/query expansion;
- be unique after normalization;
- not be a trivial morphological duplicate of another entry;
- not duplicate a phrase already present in the global/core alias corpus;
- preserve the existing domain/subdomain semantics;
- avoid vendor/product names unless the concept itself is domain-standard;
- avoid unstable news-specific or temporary terminology.

Single-word terms, acronyms and abbreviations may be curated later as aliases, but **do not count toward the +100 multi-word phrase quota**.

## 4. Phrase composition target per domain

The 100 new phrases should be intentionally diversified rather than generated as near-duplicates.

Recommended composition:

- 25 core concepts and canonical professional terms;
- 20 process/workflow phrases;
- 15 risk/control/compliance phrases where relevant;
- 15 measurement/metric/model phrases;
- 15 operational/system phrases;
- 10 specialist or advanced phrases.

This distribution is guidance, not a rigid ontology. Scientific domains may substitute experimentally appropriate categories.

## 5. Delivery stages

### Stage 0 — Corpus contract and quality guardrails

Before adding bulk vocabulary:

- retain the current `domainId` and `subdomain.id` hierarchy;
- define deterministic normalization for duplicate detection;
- define phrase-count validation per domain;
- define cross-domain collision reporting;
- define tests for blank/duplicate/overlong/underspecified entries;
- record baseline phrase counts and benchmark metrics.

Exit criterion: corpus changes can be validated automatically in CI.

### Stage 1 — Finance and regulated business

Add +100 new English phrases to each:

- `finance_banking`;
- `insurance`.

Why first: terminology is phrase-heavy, ambiguity-sensitive and highly valuable for later RU/KK transfer.

Required review themes include lending, deposits, capital, liquidity, payments, financial crime, underwriting, claims, actuarial concepts and risk.

Exit criterion: +200 unique phrases, semantic tests green, no uncontrolled expansion regression.

### Stage 2 — Industrial and infrastructure domains

Add +100 new English phrases to each:

- `energy_utilities`;
- `oil_gas_mining`;
- `manufacturing`;
- `construction_real_estate`;
- `transport_logistics`;
- `agriculture_food`.

Exit criterion: +600 phrases in this stage; cumulative +800.

### Stage 3 — Computing, medicine and life sciences

Add +100 new English phrases to each:

- `computer_science_ai`;
- `medicine_pharmacology`;
- `biology_genetics`;
- `chemistry_materials`.

Exit criterion: +400 phrases in this stage; cumulative +1,200.

### Stage 4 — Mathematics, physics, environment and human sciences

Add +100 new English phrases to each:

- `mathematics_statistics`;
- `physics_astronomy`;
- `earth_environmental_science`;
- `psychology_sociology`.

Exit criterion: +400 phrases in this stage; cumulative +1,600.

### Stage 5 — English corpus consolidation

After all domain batches:

- deduplicate normalized phrases globally;
- identify intentional cross-domain ambiguous phrases;
- check subdomain balance;
- remove weak/synthetic phrases;
- run retrieval benchmark and compare against the pre-v2 baseline;
- freeze the approved English semantic reference version.

No other language is promoted before this stage passes.

### Stage 6 — Language transfer

Port approved semantic concepts/surfaces in this order:

1. Russian (`ru`);
2. Kazakh (`kk`);
3. remaining supported languages.

Translation is not sufficient. Each target language must use real professional terminology and domain usage. English phrase identity and domain assignment remain stable while language-specific surfaces may differ structurally.

## 6. Implementation strategy

Use **one branch** for the initiative: `feature/semantic-domain-corpus-v2`.

Prefer small commits inside the branch:

- one corpus-contract/testing commit;
- one commit per domain or tightly related domain pair;
- one final consolidation/benchmark commit.

Do not create one branch per domain or language.

The initial implementation should extend the existing semantic resources and tests before considering a schema redesign. A concept-centric v3 schema may be justified later, but v2 should first establish high-quality corpus content and measurable retrieval value using the current runtime contract.

## 7. Quality rules

### Positive cases

A phrase is valuable when it resolves a professional concept more precisely than its component words. Examples:

- `capital adequacy ratio`;
- `expected credit loss`;
- `distributed transaction coordinator`;
- `adverse drug reaction`;
- `environmental impact assessment`.

### Negative cases

Reject:

- artificial keyword permutations such as `bank credit financial risk`;
- purely generic phrases such as `important process`;
- trivial singular/plural variants counted as separate entries;
- phrases whose meaning cannot be assigned confidently to the declared domain/subdomain;
- near-duplicates created only to reach the numeric quota;
- translations or aliases incorrectly inserted into the English canonical phrase list.

## 8. Required validation

CI/test coverage should verify at minimum:

- YAML parses successfully;
- domain IDs are unique;
- subdomain IDs are unique within a domain;
- no blank phrases;
- normalized phrase uniqueness within a domain;
- report cross-domain duplicates/collisions;
- exactly or at least 100 **new approved phrases per domain** relative to the recorded v1 baseline;
- phrases remain within configured semantic expansion limits at runtime;
- representative queries continue to return deterministic, bounded expansions.

A count gate alone is insufficient. The final release gate is retrieval quality.

## 9. Benchmark gates

For each completed stage, add representative queries covering:

- exact professional phrase;
- paraphrase;
- partial terminology;
- ambiguous term requiring domain context;
- abbreviation-to-full-concept path when aliases are later introduced;
- negative query where semantic expansion should not trigger.

Track at least:

- Recall@k;
- MRR/nDCG where labels exist;
- context precision;
- semantic-lane contribution;
- false-positive expansion rate;
- latency impact;
- expansion count distribution.

A domain batch should not be considered complete if phrase count increases while retrieval precision materially regresses.

## 10. Language rollout rule

Do not create independent dictionaries per language.

The English corpus is the reviewed semantic reference. Later language work maps professional local surfaces to the same semantic/domain intent. RU/KK may add language-specific terminology that has no literal English equivalent, but such additions must still receive an explicit canonical semantic mapping.

## 11. Definition of done for v2 English

Semantic Domain Corpus v2 English is complete when:

- all 16 existing domains have +100 new curated multi-word phrases;
- 1,600 additions pass normalization and collision checks;
- subdomain distribution has been reviewed;
- benchmark quality is no worse than baseline on precision/grounding and shows measurable recall improvement on terminology-heavy queries;
- corpus documentation and tests are current;
- the English reference is stable enough to begin RU translation/curation without changing concept/domain identities continuously.

## 12. First execution slice

Start with `finance_banking` only.

The first concrete batch will add **100 new English banking/finance phrases**, distributed across the existing subdomains. It serves as the calibration set for phrase quality, duplicate detection, benchmark design and review standards. Only after this batch passes quality gates should the same procedure be repeated for the remaining 15 domains.
