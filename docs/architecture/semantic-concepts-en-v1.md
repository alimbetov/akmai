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

English is only the first surface layer:

```text
canonical concept
  -> en preferred phrase
  -> future ru surface forms
  -> future kk surface forms
  -> future zh surface forms
  -> ...
```

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

## Translation rollout

Translation should happen only after the canonical English hierarchy stabilizes.

Recommended sequence:

```text
1. EN canonical phrases
2. EN retrieval/annotation quality
3. RU + KK
4. ZH
5. DE + FR + ES + PT + IT
6. TR + EL
```

For every new language, add surface forms and aliases mapped onto the existing
canonical concept IDs.

Do not translate IDs.

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
