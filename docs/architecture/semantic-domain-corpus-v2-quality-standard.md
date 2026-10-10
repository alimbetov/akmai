# Semantic Domain Corpus v2 — quality standard

Classification: **TARGET / QUALITY CONTRACT**  
Branch: `feature/semantic-domain-corpus-v2`

## Purpose

This document defines the admission standard for growing the English semantic corpus from a useful curated vocabulary into a production-grade semantic knowledge asset. Phrase count is a capacity target, not a quality definition.

## Current baseline

The canonical English corpus currently contains 384 concepts across 16 domains, 24 concepts per domain. Canonical concepts are multi-word phrases of 2–6 tokens. Existing multilingual surface packs and alias packs are retained and reused rather than replaced.

The v2 target is +400 approved English canonical phrases per domain (+6,400 total), delivered in batches of 100.

## Mandatory admission rules

Every new canonical phrase MUST:

- represent a real professional concept or stable professional phrase;
- have a clear domain and subdomain owner;
- remain useful independently of a particular vendor, temporary event or document;
- be globally unique after production normalization;
- not be a spelling, punctuation, singular/plural or wording-only duplicate used to inflate coverage;
- not duplicate an existing alias when the semantic intent is the same;
- preserve a materially distinct evidence intent when it is lexically close to another concept;
- fit the existing 2–6 token canonical phrase contract unless the contract is explicitly revised;
- be eligible for deterministic benchmark cases.

## Duplicate classes

### Hard duplicate

Exact or normalization-equivalent phrases are forbidden. Catalog construction must fail rather than silently accept them.

### Alias-equivalent wording

Two phrases that resolve to the same evidence intent should have one canonical concept. Secondary wording belongs in the alias/surface layer and does not count toward the +400 quota.

### Near duplicate

Lexically similar phrases are review candidates, not automatic failures. `SemanticCorpusNearDuplicateAuditor` identifies high-overlap pairs for human/curated disposition. Each pair must be classified as KEEP_BOTH, MERGE_CANONICAL, REJECT_DUPLICATE or REASSIGN before a batch is accepted.

### Distinct close concepts

Close wording is retained when evidence intent differs. Examples include credit risk assessment versus credit risk appetite, or facility maintenance plan versus preventive facility maintenance.

## Ambiguity contract

A generic surface that can legitimately resolve to multiple canonical concepts must be explicit in `semantic-ambiguities-en-v1.yaml`.

An ambiguity entry:

- has a normalized query surface;
- references at least two valid canonical concept IDs;
- documents why context is required;
- must never be treated as permission to duplicate canonical identities.

The registry is intentionally separate from canonical concepts and aliases. Ambiguity expresses uncertainty at query interpretation time; aliases express alternate wording for the same concept.

## Surface ownership

For English, preferred phrases and aliases must have one semantic owner after normalization. A second owner is considered a collision and must be resolved by canonicalization or an explicit future context-aware disambiguation design. The current v2 admission gate does not silently tolerate alias collisions.

## Batch review procedure

For each 100-phrase batch:

1. validate YAML and catalog construction;
2. enforce global canonical uniqueness;
3. compare the batch against the v1 baseline and all accepted v2 batches;
4. generate near-duplicate review candidates;
5. compare canonical phrases against existing aliases;
6. review ambiguous generic surfaces;
7. verify subdomain balance;
8. add positive, paraphrase, ambiguous and negative retrieval cases;
9. run retrieval-quality gates;
10. accept the batch only when recall improves without material precision/grounding regression.

## Coverage quality

No subdomain should receive most of a domain quota merely because terminology is easier to generate there. The 400 additions per domain must cover core concepts, workflows, controls/risks where relevant, metrics/models, operational systems and specialist terminology.

Numeric equality across categories is not required, but missing major professional areas is a release blocker.

## Stability and lifecycle

Canonical concept IDs become cross-language semantic identities. Once a concept is used by RU/KK or other language surfaces, renaming or moving it is a migration, not a cosmetic edit.

Deprecated terminology should normally remain as an alias/surface when historical documents can still contain it. A deprecated wording must not remain a separate canonical concept solely for compatibility.

## Release gate

Semantic Domain Corpus v2 English is not complete merely at 6,400 additions. Release requires:

- zero canonical normalization duplicates;
- zero unexplained English surface ownership collisions;
- every near-duplicate candidate dispositioned;
- ambiguity registry valid against current concept IDs;
- balanced domain/subdomain coverage;
- retrieval benchmark evidence showing terminology recall lift;
- no material precision, grounding or latency regression;
- stable enough canonical IDs to begin RU then KK curation.
