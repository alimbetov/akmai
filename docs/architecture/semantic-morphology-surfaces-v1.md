# Morphology-aware semantic surfaces v1

## Principle

Canonical ontology concepts are language independent.

A language contributes recognition surfaces only:

```text
canonical concept
  -> preferred phrase
  -> aliases
  -> lemma phrase
  -> bounded stem sequence
```

The canonical concept ID never changes when another language is added.

## Matching priority

The matcher is fail-soft and ordered by confidence:

```text
EXACT  1.00
LEMMA  0.90
STEM   0.65
```

A lower-confidence mode is evaluated only when stronger modes fail.

Stem matching is phrase-bounded: the complete stem-token sequence must occur
contiguously. A single root such as `risk`, `capital`, or `payment`
cannot activate a multi-word concept.

## Production language coverage

Language-specific morphology is active for all production languages:

```text
en ru kk zh de fr es pt it tr el
```

English examples:

```text
risk weighted assets
risk weighted asset
```

both normalize to the same lemma sequence.

A derivational case such as:

```text
payment fraud detection
payment fraud detecting
```

can fall back to the same bounded stem sequence.

## Language-specific strategies

English suffix rules are not reused as a universal morphology model.

The current implementation uses:

```text
EN              bounded English suffix morphology
RU / KK         language-specific Cyrillic/Turkic suffix morphology
ZH              Han segmentation without stemming
DE / FR / ES
PT / IT         language-specific European suffix profiles
TR              Turkish-locale agglutinative suffix handling
EL              tonos-insensitive Greek normalization and final-sigma
                canonicalization with Greek suffix rules
```

Every language owns its recognition surface behavior while all matches resolve
to the same language-independent canonical IDs.

## Safety

Morphology is a recognition mechanism, not ontology truth.

It may strengthen semantic annotation and future reranking, but it does not
hard-filter retrieval.

The vector path remains the recall safety net.
