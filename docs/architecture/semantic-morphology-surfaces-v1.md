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

## English v1

English currently provides the first morphology normalizer.

Examples:

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

## Multilingual rollout

Do not reuse English suffix rules for other languages.

Each language must implement its own `SemanticMorphologyNormalizer`.

Recommended rollout:

```text
EN
RU
KK
ZH segmentation-only
TR
DE
FR
ES
PT
IT
EL
```

For Chinese, phrase segmentation replaces stemming.

For Kazakh and Turkish, morphology should be suffix-aware and
agglutination-aware.

For German, compound handling should be introduced before broad stem matching.

## Safety

Morphology is a recognition mechanism, not ontology truth.

It may strengthen semantic annotation and future reranking, but it does not
hard-filter retrieval.

The vector path remains the recall safety net.
