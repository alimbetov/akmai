# Semantic phrase expansion v1

## Goal

Increase semantic query recall without changing the canonical concept ontology.
The first controlled expansion adds additional surface phrases (aliases) to
existing language-independent concept IDs.

## Expansion policy

The canonical ontology contains 24 concepts per root domain. A twenty-percent
increase therefore requires `ceil(24 * 0.20) = 5` additional phrases per domain.

The core pack adds:

- 16 root domains;
- 5 additional phrases per domain;
- 80 alias mappings per language;
- EN, RU and KK in the first measured tranche;
- 240 new phrases in total.

The alias pack is stored separately in
`semantic/concept-aliases-core-v1.yaml`. Additional languages can be added to
the same concept entries without changing matcher or registry code.

## Why aliases instead of new concepts

The expansion intentionally does not create 80 new canonical concept IDs.
Aliases improve wording coverage while preserving ontology stability, semantic
metadata compatibility, cross-language retrieval identity and historical
quality measurements.

## Safety gates

The quality suite enforces:

- exactly five selected concept mappings per domain in the v1 expansion;
- aliases for EN, RU and KK on every selected mapping;
- all concept IDs must already exist in the canonical catalog;
- no blank aliases;
- no normalized phrase may resolve to more than one concept within a language;
- representative aliases must resolve through the production
  `SemanticConceptMatcher` to the expected canonical concept ID;
- the existing semantic golden set and retrieval quality gates remain active.

## Next measurement stage

After this pack is stable, measure baseline versus alias-enabled query traffic
for Recall@5, Recall@10, MRR, concept assist rate, false-positive rate and p95
semantic analysis latency. Expand DE/FR/ES/PT/IT/TR/EL/ZH only with the same
collision and retrieval-quality gates.
