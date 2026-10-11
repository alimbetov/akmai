# Semantic Domain Corpus v2 — Stage 3 review

Status: TARGET / review record for `feature/semantic-domain-corpus-v2`.

## Scope

Stage 3 is now complete with four balanced English-first science and technology rounds:

- `computer_science_ai`: Batch A/B/C/D = +400, 424 total;
- `medicine_pharmacology`: Batch A/B/C/D = +400, 424 total;
- `biology_genetics`: Batch A/B/C/D = +400, 424 total;
- `chemistry_materials`: Batch A/B/C/D = +400, 424 total.

Together with the eight domains completed earlier, twelve English domains now have the target +400 curated multi-word concepts each.

The branch target is now 5,184 English concepts: 384 baseline concepts plus 4,800 additions.

## Distribution

Each Stage 3 domain preserves the existing taxonomy. Every batch contributes 25 new canonical multi-word phrases to each of the four existing subdomains. No parallel subdomain taxonomy is introduced.

Each completed Stage 3 subdomain therefore contains 106 concepts: 6 baseline + 100 additions.

## Batch A coverage

Batch A established breadth across algorithms/software systems/ML/NLP, clinical diagnosis/pharmacology/trials/epidemiology, molecular biology/genetics/cell biology/physiology, and organic/analytical/physical chemistry/materials science.

Representative additions include `distributed consensus protocol`, `model drift detection`, `adaptive trial design`, `polygenic risk score`, `chromatin accessibility analysis`, `analytical method validation`, and `fracture toughness testing`.

## Batch B coverage

Batch B deepened distributed-system safety, ML robustness, retrieval quality, population PK, causal epidemiology, DNA/RNA repair, organelle control, analytical validation and materials degradation.

Representative additions include `fencing token protocol`, `out of distribution detection`, `target trial emulation`, `mutational signature analysis`, `mitophagy quality control`, `system suitability testing`, and `grain boundary engineering`.

## Batch C coverage

Batch C moved further into mechanism-, control- and measurement-level terminology rather than synonym padding.

Representative additions include `vector clock ordering`, `direct preference optimization`, `maximum marginal relevance`, `physiologically based pharmacokinetics`, `platform trial design`, `local ancestry inference`, `ferroptosis signaling pathway`, `process mass intensity`, and `stress corrosion cracking`.

## Batch D coverage

Batch D is the specialist/advanced completion layer.

### Computer science / AI

The final round adds distributed correctness and recovery, runtime workload isolation, production model serving/governance, and RAG failure/evidence observability. Representative additions include `linearizability verification method`, `distributed transaction fencing`, `training serving skew`, `selective prediction method`, `evidence sufficiency scoring`, and `semantic retrieval observability`.

### Medicine / pharmacology

The final round adds diagnostic operations and safety, dose individualization and drug-safety mechanisms, trial execution/governance, and surveillance/transmission methodology. Representative additions include `diagnostic yield analysis`, `therapeutic drug monitoring`, `genotype guided dosing`, `risk based monitoring`, `effective reproduction number`, and `case crossover design`.

### Biology / genetics

The final round adds genome architecture and proteostasis, advanced variant interpretation and cancer/population genetics, cell-state mechanics and organelle signaling, and integrative cardiorespiratory/renal physiology. Representative additions include `topological domain organization`, `homologous repair deficiency`, `cell state plasticity`, `nuclear mechanotransduction pathway`, `coronary flow reserve`, and `cerebral autoregulation mechanism`.

### Chemistry / materials

The final round adds scalable process chemistry, analytical metrology/lifecycle control, advanced equilibrium/electrochemistry, and damage-tolerance/corrosion mechanics. Representative additions include `genotoxic impurity control`, `process analytical technology`, `metrological comparability assessment`, `Butler Volmer kinetics`, `damage tolerance analysis`, and `pitting corrosion susceptibility`.

## Quality rules

Stage 3 uses the production guardrails unchanged:

1. 2–6 canonical words;
2. global normalized canonical uniqueness;
3. stable language-independent concept IDs;
4. valid domain/subdomain ownership;
5. English preferred/alias surface ownership;
6. ambiguity registry referential integrity;
7. advisory near-duplicate review without automatic merge.

All batches are intentionally additive rather than synonym-padding. Near lexical neighbors are retained only when they describe materially different evidence, mechanisms, controls or operations; exact normalized collisions remain release-blocking.

## Release gates

Stage 3 completion is admissible only when the current branch head passes:

1. expected 5,184 total concepts;
2. 424 concepts per completed Stage 3 domain and 106 per Stage 3 subdomain;
3. canonical normalized uniqueness;
4. multi-word phrase constraints;
5. ambiguity registry referential integrity;
6. English surface ownership checks;
7. near-duplicate review with no unresolved merge-class candidates;
8. repository CI;
9. Retrieval Quality Gate;
10. Retrieval Storage Final Benchmark;
11. Production Image Build.

Until those gates complete successfully, the 5,184-concept state is implemented but provisional.

## Next expansion

Do not add Batch E to completed domains. The next English-first corpus work should target the remaining four baseline-only domains:

- `mathematics_statistics`;
- `physics_astronomy`;
- `earth_environmental_science`;
- `psychology_sociology`.

After English canonical IDs and ambiguity decisions stabilize across all 16 domains, begin multilingual transfer in order RU → KK → remaining supported languages.
