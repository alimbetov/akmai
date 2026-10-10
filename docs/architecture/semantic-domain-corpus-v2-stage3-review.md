# Semantic Domain Corpus v2 — Stage 3 review

Status: TARGET / review record for `feature/semantic-domain-corpus-v2`.

## Scope

Stage 3 now contains three balanced English-first science and technology rounds:

- `computer_science_ai`: Batch A/B/C = +300, 324 total;
- `medicine_pharmacology`: Batch A/B/C = +300, 324 total;
- `biology_genetics`: Batch A/B/C = +300, 324 total;
- `chemistry_materials`: Batch A/B/C = +300, 324 total.

The branch target is now 4,784 English concepts: 384 baseline concepts plus 4,400 additions.

## Distribution

Each Stage 3 domain keeps the existing taxonomy. Each round contributes 25 new canonical multi-word phrases to each of the four existing subdomains. No parallel subdomain taxonomy is introduced.

## Batch A coverage

Batch A established the breadth layer across algorithms/software systems/ML/NLP, clinical diagnosis/pharmacology/trials/epidemiology, molecular biology/genetics/cell biology/physiology, and organic/analytical/physical chemistry/materials science.

Representative additions include `distributed consensus protocol`, `model drift detection`, `adaptive trial design`, `polygenic risk score`, `chromatin accessibility analysis`, `analytical method validation`, and `fracture toughness testing`.

## Batch B coverage

Batch B deepened distributed-system safety, ML robustness, retrieval quality, population PK, causal epidemiology, DNA/RNA repair, organelle control, analytical validation and materials degradation.

Representative additions include `fencing token protocol`, `out of distribution detection`, `target trial emulation`, `mutational signature analysis`, `mitophagy quality control`, `system suitability testing`, and `grain boundary engineering`.

## Batch C coverage

Batch C moves further into mechanism-, control- and measurement-level terminology rather than synonym padding.

### Computer science / AI

Advanced graph/query algorithms, distributed ordering and reliability patterns, parameter-efficient/feedback-based model adaptation, and retrieval grounding/latency controls. Representative additions include `vector clock ordering`, `event sourcing pattern`, `direct preference optimization`, `maximum marginal relevance`, and `claim evidence alignment`.

### Medicine / pharmacology

Bayesian diagnostic reasoning, model-informed pharmacology, adaptive/master trial designs and modern causal epidemiology. Representative additions include `Bayesian diagnostic updating`, `physiologically based pharmacokinetics`, `platform trial design`, `directed acyclic graph`, and `quality adjusted life years`.

### Biology / genetics

Replication/transcription control, variant interpretation and population genetics, organelle quality control/cell-death programs and systems physiology. Representative additions include `replication origin firing`, `local ancestry inference`, `ferroptosis signaling pathway`, and `endothelial function assessment`.

### Chemistry / materials

Mechanistic process chemistry, analytical batch control, free-energy/interfacial chemistry and advanced degradation/fracture mechanisms. Representative additions include `catalyst turnover frequency`, `process mass intensity`, `incurred sample reanalysis`, `free energy perturbation`, and `stress corrosion cracking`.

## Quality rules

Stage 3 uses the production guardrails unchanged:

1. 2–6 canonical words;
2. global normalized canonical uniqueness;
3. stable language-independent concept IDs;
4. valid domain/subdomain ownership;
5. English preferred/alias surface ownership;
6. ambiguity registry referential integrity;
7. advisory near-duplicate review without automatic merge.

A terminology cleanup changed the epidemiology phrase `disability adjusted life` to the canonical `disability adjusted life years` before Batch A integration.

All later batches are intentionally additive rather than synonym-padding. Near lexical neighbors are retained only when they describe materially different evidence, mechanisms or operations; exact normalized collisions remain release-blocking.

## Release gates

Stage 3 Batch C is admissible only when the current branch head passes:

1. expected 4,784 total concepts;
2. 324 concepts per Stage 3 domain and 81 per Stage 3 subdomain;
3. canonical normalized uniqueness;
4. multi-word phrase constraints;
5. ambiguity registry referential integrity;
6. English surface ownership checks;
7. near-duplicate review with no unresolved merge-class candidates;
8. repository CI;
9. Retrieval Quality Gate;
10. Retrieval Storage Final Benchmark;
11. Production Image Build.

Until those gates complete successfully, the 4,784-concept state is implemented but provisional.

## Next expansion

After Batch C is green and consolidated, complete Batch D (+100 each) for these four domains. That will bring the science/technology group to the same +400 target as the eight already completed domains. Multilingual RU → KK transfer should begin only after English canonical IDs and ambiguity decisions are stable.
