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

The target is not merely a larger dictionary. The target is a **production-grade semantic corpus** with stable identities, controlled ambiguity, measurable retrieval value and explicit lifecycle rules.

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
- be globally unique after canonical normalization;
- not be a trivial morphological duplicate of another entry;
- not duplicate a phrase already present in the global/core alias corpus;
- preserve the existing domain/subdomain semantics;
- avoid vendor/product names unless the concept itself is domain-standard;
- avoid unstable news-specific or temporary terminology.

Single-word terms, acronyms and abbreviations may be curated later as aliases, but **do not count toward the +400 multi-word phrase quota**.

## 4. Production corpus quality standard

A domain is not considered production-ready only because it contains 400 new phrases. It must satisfy all of the following norms.

### 4.1 Canonicality

Every accepted phrase must represent one intended professional concept or narrowly defined semantic intent. Canonical phrases should prefer established terminology over descriptive paraphrases.

Prefer:

- `capital adequacy ratio`;
- `probability of default`;
- `liquidity coverage ratio`.

Avoid using a verbose paraphrase as a separate canonical concept when it merely restates an existing term.

### 4.2 Distinguishability

Two canonical phrases must remain separate only when a retrieval system could reasonably need to distinguish their evidence.

A useful test is:

> Would a domain expert expect materially different supporting passages for these two concepts?

If the answer is no, one phrase should normally become the canonical concept and the alternative wording should later become an alias/surface.

### 4.3 Coverage balance

No domain may satisfy its quota by overpopulating one subdomain while leaving others shallow.

Each domain batch must be reviewed for:

- subdomain balance;
- concept-type balance;
- operational vs analytical terminology;
- common vs specialist terminology;
- risk/control terminology where applicable;
- process, metric, artifact and state terminology.

Large imbalances require justification.

### 4.4 Ambiguity control

A phrase that is highly ambiguous outside its domain must not be promoted blindly as a strong expansion trigger.

Examples include generic terms such as:

- `default rate`;
- `reserve margin`;
- `stress test`;
- `model drift`;
- `exposure limit`.

Such phrases require sufficient domain context or later alias/disambiguation metadata before aggressive query expansion.

### 4.5 Alias separation

Canonical concepts, aliases, abbreviations and morphological variants are different layers.

Canonical corpus entries must not be inflated with:

- acronyms;
- plural/singular variants;
- punctuation variants;
- alternate word order;
- colloquial forms;
- translations.

Those belong to language surfaces/alias registries, not to the canonical quota.

### 4.6 Provenance and reviewability

Every 100-phrase batch must be reviewable as a coherent change. The batch should have a documented domain scope and reviewers must be able to determine why each phrase belongs in the selected domain/subdomain.

Future corpus evolution should support provenance states such as:

- `CURATED`;
- `DOMAIN_REVIEWED`;
- `BENCHMARK_VALIDATED`;
- `DEPRECATED`.

The current v2 schema does not need to encode these fields immediately, but the process must preserve this distinction so a future concept-centric schema can adopt it without semantic rework.

### 4.7 Stability

Once an English canonical phrase is used as the basis for multilingual mapping, changing its semantic identity becomes expensive. Therefore English concepts should only be frozen after duplicate, ambiguity and benchmark review.

Spelling cleanup may remain compatible; semantic reassignment across domains or meaning changes require an explicit migration decision.

### 4.8 Retrieval value

A phrase that is lexically valid but provides no measurable retrieval value should not be retained merely to meet quota.

The production bar is:

- improved terminology-heavy recall;
- no material precision regression;
- bounded semantic expansion;
- no measurable grounding degradation;
- acceptable latency impact.

## 5. Phrase composition target per domain

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

## 6. Delivery stages

### Stage 0 — Corpus contract and quality guardrails

Before adding bulk vocabulary:

- retain the current `domainId` and `subdomain.id` hierarchy;
- keep deterministic normalization in `EnglishSemanticConceptCatalog`;
- enforce global canonical phrase uniqueness at catalog load time;
- define phrase-count validation per domain;
- define near-duplicate review and consolidation rules;
- define ambiguity review;
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

- verify normalized phrase uniqueness globally;
- review all near-duplicate candidates;
- review ambiguity-sensitive phrases;
- merge only phrases that resolve to the same semantic intent;
- preserve distinct phrases when they represent materially different concepts, scopes, processes or metrics;
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

## 7. Duplicate and near-duplicate policy

The corpus distinguishes three different cases.

### 7.1 Exact and normalized duplicates — forbidden

Two canonical English phrases are duplicates when they become identical after the production normalization used by `EnglishSemanticConceptCatalog`:

- Unicode NFC normalization;
- lowercase conversion;
- punctuation/non-alphanumeric separators converted to spaces;
- repeated whitespace collapsed;
- leading/trailing whitespace removed.

Examples that must collapse to one canonical phrase:

- `Risk-Weighted Assets` / `risk weighted assets`;
- `cross-border payment` / `cross border payment`;
- `Know Your Customer` / `know your customer`.

The runtime catalog must fail fast if the same normalized canonical phrase is assigned to more than one semantic concept, including across different subdomains or domains.

### 7.2 Morphological or wording-only duplicates — consolidate

Phrases that differ only by trivial number, inflection or non-semantic wording should normally be represented by one canonical concept and later handled through aliases/surfaces.

Examples:

- `credit risk limit` / `credit risk limits`;
- `loan approval workflow` / `loan approval process` when both are used for exactly the same workflow in the corpus;
- `customer identity verification` / `verification of customer identity` when no semantic distinction is intended.

These variants must not be counted as separate entries merely to satisfy the +400 target.

### 7.3 Related but semantically distinct phrases — preserve

Lexical similarity alone is not a reason to merge phrases. Keep both entries when their professional intent is materially different.

Examples:

- `facility maintenance plan` vs `preventive facility maintenance`;
- `decision making process` vs `group decision making`;
- `credit risk assessment` vs `credit risk appetite`;
- `capital adequacy ratio` vs `internal capital assessment`.

The review rule is semantic: merge only when two phrases would retrieve essentially the same concept and evidence. Do not merge simply because they share most tokens.

### 7.4 Near-duplicate review procedure

Every 100-phrase batch must be compared against:

1. the existing v1 English corpus;
2. all previously accepted v2 batches;
3. phrases in the same subdomain;
4. phrases in other domains that share a high lexical overlap;
5. global/core aliases where the same meaning is already represented.

For every candidate pair, choose exactly one disposition:

- `KEEP_BOTH` — meanings are distinct;
- `MERGE_CANONICAL` — keep one preferred phrase and move the other wording to an alias/surface layer later;
- `REJECT_DUPLICATE` — remove the new candidate;
- `REASSIGN` — phrase belongs in another domain/subdomain.

Near-duplicate detection may use token overlap, normalized edit similarity or embeddings as a review aid, but **must not automatically delete phrases based only on a similarity threshold**.

## 8. Missing semantic norms to add before multilingual rollout

The following capabilities are part of the production target, even if some are implemented after the first English batches.

### 8.1 Ambiguity registry

Maintain an explicit list of phrases whose interpretation depends on domain context. These phrases should not receive unrestricted expansion weight.

### 8.2 Alias and abbreviation registry

Map acronyms, common shorthand and alternative professional wording to canonical concepts without increasing canonical phrase counts.

Examples:

- `PD` -> `probability of default`;
- `LGD` -> `loss given default`;
- `AML` -> `anti money laundering`.

### 8.3 Cross-domain collision review

A phrase may legitimately appear conceptually in more than one discipline, but canonical identity must remain explicit. Avoid silently copying the same surface into multiple domains.

### 8.4 Deprecation policy

Obsolete terminology should be retained as a searchable alias when historically relevant, while the canonical concept points to the current term. Deprecated terminology must not silently disappear if old documents still contain it.

### 8.5 Benchmark-linked admission

Bulk additions should progress from candidate to accepted corpus only when they pass lexical validation and representative retrieval tests. The corpus should evolve through reviewed admission rather than unchecked append-only growth.

### 8.6 Multilingual semantic invariance

When RU/KK and other languages are introduced, translated/local surfaces must map to the same underlying semantic intent rather than creating independent language-specific concepts for equivalent meanings.

## 9. Implementation strategy

Use **one branch** for the initiative: `feature/semantic-domain-corpus-v2`.

Prefer small commits inside the branch:

- one corpus-contract/testing commit;
- one commit per 100-phrase domain batch;
- one final consolidation/benchmark commit.

Do not create one branch per domain, batch or language.

The initial implementation should extend the existing semantic resources and tests before considering a schema redesign. A concept-centric v3 schema may be justified later, but v2 should first establish high-quality corpus content and measurable retrieval value using the current runtime contract.

## 10. Quality rules

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

## 11. Required validation

CI/test coverage should verify at minimum:

- YAML parses successfully;
- domain IDs are unique;
- subdomain IDs are unique within a domain;
- no blank phrases;
- normalized canonical phrases are globally unique;
- duplicate canonical phrases fail at catalog construction/startup rather than being silently accepted;
- each batch has an explicit near-duplicate review against the entire accepted corpus;
- ambiguity-sensitive phrases are identified during review;
- exactly or at least 400 **new approved phrases per domain** relative to the recorded v1 baseline;
- each 100-phrase batch is unique relative to all prior batches in that domain;
- phrases remain within configured semantic expansion limits at runtime;
- representative queries continue to return deterministic, bounded expansions.

A count gate alone is insufficient. The final release gate is retrieval quality.

## 12. Benchmark gates

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

## 13. Language rollout rule

Do not create independent dictionaries per language.

The English corpus is the reviewed semantic reference. Later language work maps professional local surfaces to the same semantic/domain intent. RU/KK may add language-specific terminology that has no literal English equivalent, but such additions must still receive an explicit canonical semantic mapping.

## 14. Definition of done for v2 English

Semantic Domain Corpus v2 English is complete when:

- all 16 existing domains have +400 new curated multi-word phrases;
- 6,400 additions pass global normalization/duplicate checks;
- every 100-phrase batch has completed near-duplicate review;
- wording-only duplicates have been consolidated rather than counted separately;
- semantically distinct close phrases are preserved intentionally;
- ambiguity-sensitive concepts are identified and not promoted blindly;
- subdomain distribution has been reviewed;
- alias/abbreviation/deprecation rules are defined before multilingual rollout;
- benchmark quality is no worse than baseline on precision/grounding and shows measurable recall improvement on terminology-heavy queries;
- corpus documentation and tests are current;
- the English reference is stable enough to begin RU translation/curation without changing concept/domain identities continuously.

## 15. First execution slice

Start with `finance_banking` only.

The domain is delivered as four sequential curated batches:

1. Batch A: first +100 phrases;
2. Batch B: additional +100;
3. Batch C: additional +100;
4. Batch D: additional +100.

Total: **+400 new English finance/banking phrases**.

Before each batch is accepted, run the duplicate/near-duplicate and ambiguity review defined above. The first batch serves as the calibration set for phrase quality, duplicate detection, benchmark design and review standards. Subsequent batches expand coverage while being checked against all previously accepted phrases. Only after `finance_banking` reaches +400 and passes quality gates should the same procedure be repeated for the remaining 15 domains.
