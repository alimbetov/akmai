# Semantic Intelligence Layer for Adaptive Retrieval

## Purpose

The Adaptive Chunk Graph is most useful when historic association strength is
combined with a stable semantic coordinate system.

AkmAI therefore treats the 11-language lexicon, ontology map and adaptive chunk
associations as three distinct but cooperating layers.

```text
11-language Lexicon
       |
       v
Semantic Concepts
       |
       v
Ontology Map
       |
       +--> query routing
       +--> lexical expansion
       +--> chunk annotation
       +--> association compatibility
       |
       v
Adaptive Chunk Graph
```

## Do not expand KnowledgeDomain into an industry taxonomy

The current `KnowledgeDomain` values `GENERAL`, `LEGAL`, and `MEDICAL`
participate in chunking semantics.

They should not become a 20–100 value industry enum.

Use orthogonal semantic dimensions instead:

```text
document kind
sector(s)
subdomain(s)
facet(s)
concept(s)
semantic type(s)
```

Examples:

```text
sector    = FINANCE_BANKING
subdomain = PAYMENTS
facets    = REGULATORY, PRIVACY, CYBERSECURITY
concepts  = DATA_BREACH, NOTIFICATION, DEADLINE
```

## Lexicon model

A concept is language-neutral. Each supported language provides a small set of
high-precision surface forms.

Supported languages:

```text
kk, ru, en, zh, de, fr, es, pt, it, tr, el
```

Start with 2–3 semantic anchors per language/concept rather than attempting an
unbounded synonym list.

Example:

```yaml
id: contract_termination
terms:
  ru:
    preferred: расторжение договора
    anchors:
      - прекращение договора
      - отказ от договора
  en:
    preferred: contract termination
    anchors:
      - termination of agreement
      - cancellation of contract
```

The concept is the stable unit. Language strings are surfaces.

## Context profiles

Each concept can accumulate a versioned semantic context profile:

```text
CONTRACT_TERMINATION
  concepts:
    MATERIAL_BREACH   0.91
    NOTICE            0.88
    DEADLINE          0.84
  sectors:
    CIVIL_LAW         0.96
  facets:
    LEGAL             1.00
```

These profiles are corpus-derived evidence, not uncontrolled automatic ontology
truth.

New candidate relations pass through explicit lifecycle/quality gates before
becoming confirmed ontology relations.

## Ontology node types

Initial node types:

```text
SECTOR
SUBDOMAIN
FACET
CONCEPT
ROLE
PROCESS
STATE
EVENT
MEASURE
```

Initial relation vocabulary:

```text
IS_A
PART_OF
BROADER
NARROWER
RELATED_TO
APPLIES_TO
REQUIRES
PERMITS
PROHIBITS
CAUSES
AFFECTS
MEASURED_BY
REGULATED_BY
OPPOSITE_OF
EXCLUDES
```

Negative/opposite relations are first-class because embedding similarity alone
can place semantically opposite statements near each other.

## Sector and facet strategy

Do not make LEGAL, REGULATORY, SECURITY or PRIVACY mutually exclusive root
industries.

Use sectors for economic/activity areas and facets for viewpoints.

Candidate modern sector families:

```text
FINANCE_BANKING
INSURANCE
ACCOUNTING_TAX_AUDIT
HEALTHCARE
PHARMA_BIOTECH
SOFTWARE_IT
DATA_AI
CYBERSECURITY
TELECOM_NETWORKING
GOVERNMENT_PUBLIC_SECTOR
EDUCATION_RESEARCH
HR_EMPLOYMENT
ENERGY_UTILITIES
OIL_GAS_MINING
MANUFACTURING_ENGINEERING
CONSTRUCTION_REAL_ESTATE
TRANSPORT_LOGISTICS
PROCUREMENT_SUPPLY_CHAIN
RETAIL_ECOMMERCE
AGRICULTURE_FOOD
MEDIA_NEWS
ENVIRONMENT_ESG
SALES_MARKETING
BUSINESS_MANAGEMENT
```

Candidate cross-cutting facets:

```text
LEGAL
REGULATORY
COMPLIANCE
RISK
FINANCIAL
ACCOUNTING
TECHNICAL
SECURITY
PRIVACY
SAFETY
OPERATIONS
PROCESS
CLINICAL
PHARMACOLOGY
RESEARCH
POLICY
TEMPORAL
NEWS
```

This taxonomy is data-driven and versioned rather than encoded as a large Java
enum.

## Query analysis

A query is mapped to a soft semantic profile:

```text
question
 -> language
 -> longest phrase matches
 -> exact concept matches
 -> ambiguity resolution
 -> sectors/facets
 -> bounded ontology neighbourhood
```

Do not pick exactly one domain.

Example:

```text
FINANCE_BANKING   0.88
PRIVACY           0.92
CYBERSECURITY     0.81
REGULATORY        0.79
```

The router keeps multiple compatible nodes.

## Retrieval integration

The vector query remains the original semantic query.

Ontology expansion is used for:

- bounded language-local lexical expansion;
- semantic annotations;
- reranking features;
- adaptive-graph candidate compatibility.

Global vector retrieval remains a safety net against ontology classification
errors.

## Adaptive graph compatibility

Given seed chunk A and learned neighbour B:

```text
historic edge A-B       = 0.84
query compatibility B   = 0.92
```

the adaptive expansion score can use both.

A historically strong edge with poor compatibility for the current query is
discarded before context assembly.

## Ingestion placement

Ontology annotation belongs in ingestion enrichment rather than inside
`SemanticChunker`.

Target:

```text
SemanticChunker
 -> KnowledgeChunk
 -> ParallelIngestionExecutor
      IdentifierExtractor
      OntologyAnnotator
 -> EnrichedKnowledgeChunk
 -> PersistenceCoordinator
```

Chunking answers "where is the semantic boundary?"

Ontology annotation answers "what does this chunk mean?"

Keeping them separate allows ontology evolution without destabilizing chunk
boundaries.

## Storage rollout

Phase 1 can store annotations in existing projection/vector metadata:

```json
{
  "ontologyVersion": "v1",
  "ontology": {
    "sectors": ["finance_banking"],
    "facets": ["privacy", "regulatory"],
    "concepts": ["personal_data", "data_breach", "notification"]
  }
}
```

Do not depend on JSONB filtering as the final high-QPS ontology retrieval index.

After quality is proven, introduce a generation/ACL-aware semantic tag projection
and an ONTOLOGY retrieval channel.

## Ten enrichment waves

Each industry/subdomain is enriched through the same controlled program:

```text
Wave 1  core concepts and 2–3 high-precision anchors
Wave 2  actions/processes
Wave 3  actors/roles
Wave 4  states/outcomes
Wave 5  normative/decision semantics
Wave 6  measures/units/thresholds
Wave 7  temporal/lifecycle semantics
Wave 8  graph relations
Wave 9  ambiguity/opposites/exclusions
Wave 10 corpus-derived weights and topology tuning
```

A wave is judged by retrieval quality and false-positive control, not dictionary
size.

## Learning boundary

The adaptive graph may propose ontology candidates:

```text
behavioural association
 -> candidate semantic relation
```

but it must never automatically promote request traffic into authoritative
ontology.

Promotion remains a validated data pipeline.
