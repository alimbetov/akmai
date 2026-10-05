# Semantic Domain Corpus v2

## Scope

This layer expands AkmAI from a small industry-profile taxonomy into a
multilingual semantic coordinate system covering economic sectors and scientific
disciplines.

It does not expand `KnowledgeDomain`. The stable chunking domains remain:

```text
GENERAL
LEGAL
MEDICAL
```

Semantic domains are orthogonal annotations.

## Root semantic domains

Economic sectors:

```text
finance_banking
insurance
energy_utilities
oil_gas_mining
manufacturing
construction_real_estate
transport_logistics
agriculture_food
```

Scientific disciplines:

```text
mathematics_statistics
physics_astronomy
chemistry_materials
biology_genetics
computer_science_ai
medicine_pharmacology
earth_environmental_science
psychology_sociology
```

The catalog therefore contains 16 root domains.

## Language contract

Every domain must cover all production languages:

```text
kk ru en zh de fr es pt it tr el
```

A catalog entry missing a localized name or fewer than three curated anchors for
any supported language fails closed during catalog construction.

## Two-tier lexicon

A large vocabulary must not turn into a large false-positive surface.

AkmAI therefore separates two levels.

### Tier 1: curated anchors

Curated language-local anchors are high precision:

```text
anchor = true
weight = 1.0
```

Only these anchors and localized domain names are used by the initial
`SemanticDomainRouter`.

Examples:

```text
finance_banking / en
  bank
  credit
  interest rate

computer_science_ai / ru
  алгоритм
  искусственный интеллект
  машинное обучение
```

### Tier 2: semantic enrichment

Each supported language supplies 19 semantic qualifiers and 19 semantic aspects.
For every curated anchor the registry lazily creates bounded semantic surfaces:

```text
qualifier + anchor + aspect
```

With at least three anchors this yields:

```text
3 × 19 × 19 = 1083
```

generated surfaces plus the anchors themselves.

The runtime contract is stricter than the arithmetic expectation:

```text
entryCount(domain, language) > 1000
```

after normalization and deduplication.

Generated terms are deliberately lower confidence:

```text
anchor = false
weight = 0.35
```

They are intended for bounded enrichment, ontology expansion and reranking, not
for primary domain classification.

## Size

The mandatory quality gate checks every one of:

```text
16 domains × 11 languages = 176 domain-language pairs
```

and requires more than 1000 semantic entries for every pair.

The total semantic surface count must therefore exceed 176,000 entries.

The lexicon is generated lazily and cached per domain-language pair rather than
materializing the entire corpus at application startup.

## Routing

`SemanticDomainRouter` is multi-label.

A query may map to more than one compatible semantic domain:

```text
"Bank credit risk and climate ecosystem exposure"

finance_banking
earth_environmental_science
```

Latin/Cyrillic/Greek/Turkish matching uses Unicode word boundaries to avoid
substring errors such as matching `bank` inside `bankruptcy`.

Chinese uses continuous-script substring matching.

The original vector query is not rewritten or filtered by domain routing.

## Ingestion integration

Semantic annotations are added after chunking:

```text
SemanticChunker
  -> KnowledgeChunk
  -> SemanticChunkAnnotator
  -> identifiers/references
  -> SearchProjection
  -> vector/search metadata
```

Example metadata:

```json
{
  "semanticOntologyVersion": "semantic-domain-v2",
  "semanticDomains": [
    "computer_science_ai"
  ],
  "semanticDomainScores": {
    "computer_science_ai": 2.18
  }
}
```

At most three domains are attached to a chunk.

No chunk boundary is changed by semantic annotation, and absence of a confident
domain leaves the original chunk unchanged.

## Retrieval safety

This phase intentionally does not hard-filter retrieval by semantic domain.

Semantic classification errors must not destroy recall.

Safe rollout order:

```text
1. ingest annotations
2. observe domain distribution
3. add bounded rerank overlap
4. measure retrieval quality
5. only then consider a dedicated ontology retrieval channel
```

## Quality gates

The retrieval quality workflow includes:

- domain catalog balance;
- 11-language completeness;
- >1000 entries for every domain-language pair;
- total corpus >176,000 entries;
- anchor/high-weight versus generated/low-weight separation;
- router coverage across every domain and language;
- boundary false-positive protection;
- Chinese continuous-script routing;
- semantic chunk annotation;
- search-projection metadata propagation.

This keeps corpus size a verifiable contract while preventing dictionary size
from becoming a proxy for retrieval quality.
