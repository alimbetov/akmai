# Multilingual Semantic Lexicon Program

## Purpose

AkmAI needs a semantic search layer that works consistently across the 11 supported
languages without maintaining pairwise language translations or expanding vector
queries with uncontrolled synonym text.

Supported language contract:

`kk, ru, en, zh, de, fr, es, pt, it, tr, el`

The semantic library is concept-centric:

```text
                 concept: dosage
                       |
      +--------+--------+--------+--------+
      |        |        |        |        |
     kk       ru       en       zh       de ...
      |        |        |        |        |
    доза     доза     dose      剂量      Dosis
```

A concept has one stable ID and language-specific surface forms. Cross-language
alignment therefore grows approximately as O(concepts x languages), not
O(languages²).

## Runtime architecture

```text
src/main/resources/semantic/
        |
        | application startup
        v
SemanticLexiconLoader
        |
        +--> schema validation
        +--> 11-language coverage validation
        +--> duplicate/ambiguity validation
        +--> normalization
        v
ImmutableSemanticLexicon
        |
        +--> conceptId -> concept
        +--> language -> normalized term -> concept ids
        +--> industry -> concepts
        +--> semantic type -> concepts
        |
        v
SemanticQueryAnalyzer
        |
        +--> exact lexical concepts
        +--> phrase concepts
        +--> semantic intent tags
        +--> bounded language-local expansion
        |
        +-----------------------------+
        |                             |
        v                             v
Vector query                    Lexical query
ORIGINAL semantic text          bounded enriched terms
(no synonym stuffing)           same language only
        |                             |
        +-------------+---------------+
                      v
                ResultFusion
                      |
                      v
              SemanticReranker
```

The dictionary itself is fully preloaded and immutable. It is not a cache.

A separate bounded hot cache stores the result of semantic query analysis:

```text
(language, industry, normalized query)
                 |
                 v
          W-TinyLFU cache
                 |
       +---------+----------+
       |                    |
      hit                  miss
       |                    |
       |             analyze lexicon
       |                    |
       +---------<----------+
```

Recommended implementation: Caffeine maximum-weight cache with Window TinyLFU.
Frequency changes cache residency only. It must never modify semantic relevance
weights.

## Resource layout

```text
src/main/resources/semantic/
  common/
    wave-01-core.yaml
    ...
    wave-10-corpus-tuning.yaml

  industries/
    civil_law/
      wave-01-core.yaml
      wave-02-actions.yaml
      wave-03-actors.yaml
      wave-04-states.yaml
      wave-05-normative.yaml
      wave-06-measures.yaml
      wave-07-temporal.yaml
      wave-08-relations.yaml
      wave-09-ambiguity.yaml
      wave-10-corpus-tuning.yaml

    cardiology/
      wave-01-core.yaml
      ...
      wave-10-corpus-tuning.yaml

    software/
      wave-01-core.yaml
      ...
      wave-10-corpus-tuning.yaml
```

The same ten-wave contract applies to every industry. Common concepts may be
referenced from every industry instead of duplicated.

## Concept schema

Each resource file contains concepts, not pairwise translations.

```yaml
industry: cardiology
wave: 1
version: 1

concepts:
  - id: dosage
    semanticType: DOSAGE
    weight: 1.0
    expansion:
      maxTerms: 3
    terms:
      kk:
        preferred: доза
        aliases: [дозалау, мөлшер]
      ru:
        preferred: доза
        aliases: [дозировка]
      en:
        preferred: dose
        aliases: [dosage]
      zh:
        preferred: 剂量
        aliases: [用量]
      de:
        preferred: Dosis
        aliases: [Dosierung]
      fr:
        preferred: dose
        aliases: [posologie]
      es:
        preferred: dosis
        aliases: [dosificación]
      pt:
        preferred: dose
        aliases: [dosagem]
      it:
        preferred: dose
        aliases: [dosaggio]
      tr:
        preferred: doz
        aliases: [dozaj]
      el:
        preferred: δόση
        aliases: [δοσολογία]
```

Optional fields for later waves:

```yaml
    phrases: {}
    acronyms: {}
    exclusions: {}
    broader: []
    narrower: []
    related: []
    units: []
    patterns: []
    sourceNotes: []
```

`exclusions` are critical for preventing false positive expansion.

## Ten enrichment waves per industry

### Wave 1 — Core concepts

Canonical nouns and high-value domain entities.

Examples:
- cardiology: dose, contraindication, blood pressure, arrhythmia
- civil law: contract, party, obligation, termination
- software: API, endpoint, request, response, deployment

Goal: high precision and broad everyday coverage.

### Wave 2 — Actions and processes

Verbs, operations and process names.

Examples:
- prescribe / administer / monitor
- sign / terminate / transfer
- deploy / rollback / authenticate

Goal: retrieve procedures even when nouns differ from the query wording.

### Wave 3 — Actors and roles

People, institutions, systems and legal/technical roles.

Examples:
- patient, physician, insurer
- claimant, debtor, creditor
- client, server, operator, administrator

Goal: role-aware semantic matching.

### Wave 4 — States, properties and outcomes

Conditions, states, attributes and result vocabulary.

Examples:
- stable / acute / elevated
- valid / void / overdue
- healthy / degraded / unavailable

Goal: support state-oriented questions.

### Wave 5 — Normative and decision semantics

Obligation, prohibition, permission, indication, contraindication, requirement,
exception and eligibility language.

Goal: strengthen legal/medical/regulated retrieval while preserving the existing
`SemanticUnitType` taxonomy.

### Wave 6 — Measures, units and thresholds

Quantities, units, limits, numeric relationships and domain-specific measurement
phrases.

Examples:
- mg, mmol/L, bpm
- days, percentages, monetary thresholds
- ms, MiB, QPS, timeout

Goal: make numeric evidence retrievable without turning numbers into free-form
synonyms.

### Wave 7 — Temporal semantics

Deadlines, recurrence, duration, ordering, lifecycle and effective-date concepts.

Examples:
- before / after / within / no later than
- daily / weekly
- active / archived / expired / effective from

Goal: improve time-bound and news-like retrieval.

### Wave 8 — Relations and reference semantics

Cross-reference, part-whole, dependency, causality and relation vocabulary.

Examples:
- pursuant to / defined in / except under
- caused by / associated with
- depends on / calls / implements

Goal: cooperate with `cross-reference-hardening` rather than duplicate it.

### Wave 9 — Ambiguity and hard negatives

False friends, overloaded terms, abbreviations with multiple meanings and
industry-specific exclusions.

Examples:
- "lead" metal vs leadership
- "claim" insurance vs legal claim
- "port" network port vs physical port

Goal: reduce false-positive expansion. This wave is mandatory before an industry
is considered mature.

### Wave 10 — Corpus-derived tuning

Terms are added, weighted or removed using actual AkmAI corpora and retrieval
failures.

Inputs:
- missed relevant chunks;
- false positives;
- query logs after privacy-safe aggregation;
- lexical fallback misses;
- reranker disagreements;
- industry benchmark cases.

Goal: production tuning, not dictionary size.

## Wave quality gates

A wave is complete only when all of these hold:

1. YAML/schema validation passes.
2. Concept IDs are globally stable and unique.
3. Every production concept either covers all 11 languages or explicitly records
   an approved language gap.
4. No normalized term silently maps to incompatible concepts without an
   ambiguity declaration.
5. Existing multilingual retrieval regression tests remain green.
6. Industry-specific golden queries improve or remain neutral.
7. False-positive rate does not regress beyond the agreed threshold.
8. Expansion remains bounded.
9. Vector queries remain unexpanded.
10. Exact identifier and exact reference authority always outrank synonym-derived
    evidence.

## Semantic query algorithm

For each query chunk:

```text
Q0 = original semanticText

1. normalize Unicode and language
2. longest-phrase concept matching
3. exact-token concept matching
4. resolve declared ambiguity using:
      industry profile
      neighboring matched concepts
      semantic intent
5. emit concept IDs + confidence
6. build bounded lexical expansion:
      preferred form
      at most N aliases
      current language only
7. send:
      Q0 -> vector retrieval
      Q0 + expansion -> lexical retrieval
8. fuse independent evidence with RRF
9. retain identifier/reference authority tiers
10. rerank against original question, not expanded text
```

Expansion constraints:
- no cross-language expansion by default;
- no query expansion for identifiers;
- no expansion of numbers or units without a matching concept;
- max concepts per query chunk;
- max aliases per concept;
- max total expansion characters/tokens;
- negative/exclusion rules are applied before expansion.

## Confidence model

Suggested initial deterministic confidence:

```text
preferred exact phrase     1.00
alias exact phrase         0.95
preferred token            0.90
alias token                0.85
ambiguous resolved         <= 0.75
```

The numbers are configuration defaults, not learned truth. Wave 10 may tune them
from benchmark evidence.

## Cache design

Cache key:

```text
lexiconVersion
+ language
+ industryProfile
+ normalizedSemanticText
```

Cache value:

```text
SemanticQueryAnalysis {
  matchedConcepts,
  lexicalExpansion,
  semanticTypes,
  ambiguityDecisions
}
```

Requirements:
- bounded maximum weight;
- W-TinyLFU admission/eviction;
- immutable cache values;
- no negative unbounded cache;
- expose hit/miss/eviction/load metrics;
- lexicon version in the key prevents stale semantic expansions after a library
  update;
- application startup fails closed on malformed semantic resources.

## Rollout strategy

Do not attempt all industries and all ten waves in one pull request.

Recommended program:

```text
PR 1  semantic runtime + schema + cache + common wave 1
PR 2  first industry wave 1
PR 3  first industry wave 2
...
PR 11 first industry wave 10
```

Once the process is stable, multiple industries may be enriched in parallel, but
each industry keeps an independent quality ledger.

## Initial order

Recommended calibration order:

1. civil_law — strongest reference/obligation test bed;
2. cardiology — strongest atomic medical semantics test bed;
3. software — strongest technical/identifier/API test bed.

Only after those three pass all ten waves should the same process be replicated
widely. This validates the model against three materially different semantic
domains before scaling the library.
