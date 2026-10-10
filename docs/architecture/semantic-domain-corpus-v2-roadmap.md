# Semantic Domain Corpus v2 — English-first roadmap

Classification: **TARGET / IMPLEMENTATION ROADMAP**  
Branch: `feature/semantic-domain-corpus-v2`  
Initial language: **English (`en`)**  
Migration strategy: **English canonical corpus first, then language surfaces are ported onto stable concept/domain identities**

## 1. Goal

Expand AkmAI's semantic knowledge layer without adding another retrieval lane.

The first release establishes a substantially deeper English professional phrase corpus. Each existing semantic `domainId` receives **400 new curated multi-word professional phrases**. This is the original +100 target plus **+300 additional new phrases per domain**. Existing phrases remain in place and are reviewed separately; they do not count toward the +400 quota.

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

Target expansion: **6,400 new English phrases** in total.

## 3. What counts toward the 400-phrase quota

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

Single-word terms, acronyms and abbreviations may be curated later as aliases, but **do not count toward the +400 multi-word phrase quota**.

## 4. Phrase composition target per domain

The 400 new phrases should be intentionally diversified rather than generated as near-duplicates.

Recommended composition:

- 100 core concepts and canonical professional terms;
- 80 process/workflow phrases;
- 60 risk/control/compliance phrases where relevant;
- 60 measurement/metric/model phrases;
- 60 operational/system phrases;
- 40 specialist or advanced phrases.

This distribution is guidance, not a rigid ontology. Scientific domains may substitute experimentally appropriate categories.

To keep review manageable, each domain is delivered in **four internal batches of 100 phrases**. A later batch must not repeat, lightly rephrase or mechanically extend earlier batches merely to satisfy quota.

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

Add +400 new English phrases to each:

- `finance_banking`;
- `insurance`.

Why first: terminology is phrase-heavy, ambiguity-sensitive and highly valuable for later RU/KK transfer.

Required review themes include lending, deposits, capital, liquidity, payments, financial crime, underwriting, claims, actuarial concepts and risk.

Exit criterion: +800 unique phrases, semantic tests green, no uncontrolled expansion regression.

### Stage 2 — Industrial and infrastructure domains

Add +400 new English phrases to each:

- `energy_utilities`;
- `oil_gas_mining`;
- `manufacturing`;
- `construction_real_estate`;
- `transport_logistics`;
- `agriculture_food`.

Exit criterion: +2,400 phrases in this stage; cumulative +3,200.

### Stage 3 — Computing, medicine and life sciences

Add +400 new English phrases to each:

- `computer_science_ai`;
- `medicine_pharmacology`;
- `biology_genetics`;
- `chemistry_materials`.

Exit criterion: +1,600 phrases in this stage; cumulative +4,800.

### Stage 4 — Mathematics, physics, environment and human sciences

Add +400 new English phrases to each:

- `mathematics_statistics`;
- `physics_astronomy`;
- `earth_environmental_science`;
- `psychology_sociology`.

Exit criterion: +1,600 phrases in this stage; cumulative +6,400.

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
- one commit per 100-phrase domain batch;
- one final consolidation/benchmark commit.

Do not create one branch per domain, batch or language.

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
- exactly or at least 400 **new approved phrases per domain** relative to the recorded v1 baseline;
- each 100-phrase batch is unique relative to all prior batches in that domain;
- phrases remain within configured semantic expansion limits at runtime;
- representative queries continue to return deterministic, bounded expansions.

A count gate alone is insufficient. The final release gate is retrieval quality.

## 9. Benchmark gates

For each completed 100-phrase batch, add representative queries covering:

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

- all 16 existing domains have +400 new curated multi-word phrases;
- 6,400 additions pass normalization and collision checks;
- subdomain distribution has been reviewed;
- benchmark quality is no worse than baseline on precision/grounding and shows measurable recall improvement on terminology-heavy queries;
- corpus documentation and tests are current;
- the English reference is stable enough to begin RU translation/curation without changing concept/domain identities continuously.

## 12. First execution slice

Start with `finance_banking` only.

The domain is delivered as four sequential curated batches:

1. Batch A: first +100 phrases;
2. Batch B: additional +100;
3. Batch C: additional +100;
4. Batch D: additional +100.

Total: **+400 new English finance/banking phrases**.

The first batch serves as the calibration set for phrase quality, duplicate detection, benchmark design and review standards. Subsequent batches expand coverage while being checked against all previously accepted phrases. Only after `finance_banking` reaches +400 and passes quality gates should the same procedure be repeated for the remaining 15 domains.
