# English phrase-first semantic concepts v1

## Decision

The canonical semantic layer is phrase-first.

Single tokens remain useful as weak domain anchors, but primary concepts are
curated 2-6 word expressions because they carry more information and produce
fewer false positives.

Examples:

```text
capital adequacy ratio
drug dose adjustment
machine learning model
natural language processing
supply chain disruption
environmental impact assessment
```

This is materially safer than treating generic words such as capital, model,
risk, network, cell, or rate as authoritative concepts.

## Canonical identity

Concept IDs are language-independent.

Example:

```text
finance_banking.risk_capital.capital_adequacy_ratio
```

English defines the canonical phrase identity, while every production
language provides recognition surfaces for the same concept IDs:

```text
canonical concept
  -> en preferred phrase
  -> ru / kk / zh surfaces
  -> de / fr / es / pt / it surfaces
  -> tr / el surfaces
```

All 11 production languages currently provide one preferred surface for every
canonical concept. With 384 canonical concepts this yields 4,224 preferred
language-specific surfaces.

Translations must reuse the same canonical ID rather than creating new concepts.

## Current corpus

The v1 English pack covers all 16 semantic domains.

Each domain currently contains:

```text
4 subdomains
x
6 curated phrase concepts
=
24 concepts
```

Across the catalog:

```text
16 domains
64 subdomains
384 curated English phrase concepts
```

The corpus is intentionally curated rather than generated from the >176k
low-weight enrichment surfaces in semantic-domain-v2.

The two layers have different jobs:

```text
domain anchors / generated enrichment
    -> broad semantic context

curated phrase concepts
    -> precise canonical meaning
```

## Matching

English concept matching uses normalized Unicode-aware word boundaries.

A phrase concept can classify a chunk even when the domain anchor is absent.

Example:

```text
"The capital adequacy ratio exceeded the regulatory minimum."

concept:
finance_banking.risk_capital.capital_adequacy_ratio

domain:
finance_banking
```

This is stronger than requiring a separate occurrence of bank, credit, or
interest rate.

Multiple domains and concepts are allowed in the same chunk.

## Ingestion metadata

For English chunks, phrase concepts are persisted as explainable metadata:

```json
{
  "semanticConceptVersion": "semantic-concepts-en-v1",
  "semanticConcepts": [
    "computer_science_ai.data_nlp.natural_language_processing",
    "computer_science_ai.data_nlp.large_language_model"
  ],
  "semanticConceptPhrases": [
    "natural language processing",
    "large language model"
  ]
}
```

At most 12 concepts are attached to one chunk.

Concept matches also contribute to soft semantic-domain scores.

They do not hard-filter retrieval.

## Multilingual surface status

The translation rollout is complete for the current production language set:

```text
en ru kk zh de fr es pt it tr el
```

Every language pack is required to cover the exact same 384 canonical concept
IDs. Language-specific surface text may change in a future version, but
canonical IDs are never translated or duplicated.

The quality gate round-trips all 4,224 preferred surfaces through the production
matcher and requires each surface to resolve back to its canonical concept.

## Next ontology depth

The current hierarchy is:

```text
DOMAIN
  -> SUBDOMAIN
      -> CONCEPT
```

After concept quality is stable, concepts can acquire orthogonal typed
annotations:

```text
FACET
PROCESS
ROLE
STATE
EVENT
MEASURE
```

and typed relations:

```text
IS_A
PART_OF
RELATED_TO
REQUIRES
CAUSES
AFFECTS
MEASURED_BY
OPPOSITE_OF
EXCLUDES
```

That layer should be added only after retrieval tests show that phrase concepts
improve precision without harming recall.


## Staged lexical retrieval integration

The first retrieval rollout uses canonical concepts as a bounded lexical
normalization signal for only the three initial languages:

```text
en
ru
kk
```

The original lexical query is always executed first. When morphology or an
alias resolves the query to a canonical concept whose preferred phrase is not
already present in the query, lexical retrieval may run up to two additional
canonical-phrase searches.

Semantic expansion is deliberately quota bounded:

```text
maximum semantic query expansions = 2
maximum semantic result slots      = 2
```

The merged result preserves the baseline lexical ranking and reserves only a
small tail quota for new semantic hits. The other production languages keep
their existing lexical behavior in this rollout.

Generated >1000-term domain lexicons are not injected into FTS queries. They
remain low-confidence enrichment data; using them as unrestricted query
expansion would increase false-positive and latency risk.

## Semantic golden set

A deterministic golden set contains 120 query cases:

```text
40 English
40 Russian
40 Kazakh
```

The 40 canonical concepts are stratified across all 16 root semantic domains.
Each case carries the expected language-independent `conceptId`, and the
retrieval quality workflow requires the production `SemanticQueryAnalyzer`
to resolve it correctly.

This golden set is a query-understanding regression contract. It does not by
itself prove a production recall percentage; retrieval lift must be measured
against a corpus-backed benchmark with expected chunks/documents.
