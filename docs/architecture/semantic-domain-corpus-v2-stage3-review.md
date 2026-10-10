# Semantic Domain Corpus v2 — Stage 3 review

Status: TARGET / review record for `feature/semantic-domain-corpus-v2`.

## Scope

Stage 3 now contains two balanced English-first science and technology rounds:

- `computer_science_ai`: Batch A +100 and Batch B +100 = +200, 224 total;
- `medicine_pharmacology`: Batch A +100 and Batch B +100 = +200, 224 total;
- `biology_genetics`: Batch A +100 and Batch B +100 = +200, 224 total;
- `chemistry_materials`: Batch A +100 and Batch B +100 = +200, 224 total.

The branch target is now 4,384 English concepts: 384 baseline concepts plus 4,000 additions.

## Distribution

Each Stage 3 domain keeps the existing taxonomy. Each round contributes 25 new canonical multi-word phrases to each of the four existing subdomains. No parallel subdomain taxonomy is introduced.

## Batch A coverage

### Computer science / AI

Algorithms and data structures, distributed/software systems, machine learning, retrieval/NLP and LLM operations. Representative additions include `amortized complexity analysis`, `distributed consensus protocol`, `model drift detection`, and `retrieval augmented generation`.

### Medicine / pharmacology

Clinical diagnosis, pharmacokinetics/pharmacodynamics, clinical-trial design and epidemiology. Representative additions include `diagnostic test specificity`, `receptor occupancy model`, `adaptive trial design`, and `vaccine effectiveness estimate`.

### Biology / genetics

Molecular regulation, population and medical genetics, cell biology and physiology. Representative additions include `chromatin accessibility analysis`, `polygenic risk score`, `mitochondrial membrane potential`, and `glomerular filtration rate`.

### Chemistry / materials

Organic synthesis, analytical chemistry, physical chemistry and materials characterization. Representative additions include `retrosynthetic analysis method`, `analytical method validation`, `electrochemical impedance spectroscopy`, and `fracture toughness testing`.

## Batch B coverage

### Computer science / AI

Batch B deepens data-structure and algorithmic internals, distributed-system safety, modern ML robustness, retrieval/ranking and LLM-quality operations. Representative additions include `dynamic connectivity algorithm`, `fencing token protocol`, `out of distribution detection`, `reciprocal rank fusion`, and `answer grounding evaluation`.

### Medicine / pharmacology

Batch B deepens diagnostic decision theory, population/nonlinear PK, trial estimands and endpoint statistics, and causal epidemiology. Representative additions include `pretest probability estimate`, `population pharmacokinetic model`, `estimand definition framework`, and `target trial emulation`.

### Biology / genetics

Batch B deepens DNA/RNA repair and regulation, sequencing/population-genetics analysis, organelle/cell-state control and systems physiology. Representative additions include `double strand break repair`, `mutational signature analysis`, `mitophagy quality control`, and `ventilation perfusion matching`.

### Chemistry / materials

Batch B deepens synthetic route design, quantitative analytical validation, thermochemical/electrochemical modeling and structure-property degradation. Representative additions include `reaction impurity profiling`, `system suitability testing`, `kinetic isotope effect`, and `grain boundary engineering`.

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

Batch B is intentionally additive rather than synonym-padding. Near lexical neighbors are retained only when they describe materially different evidence or operations; exact normalized collisions remain release-blocking.

## Release gates

Stage 3 Batch B is admissible only when the current branch head passes:

1. expected 4,384 total concepts;
2. 224 concepts per Stage 3 domain and 56 per Stage 3 subdomain;
3. canonical normalized uniqueness;
4. multi-word phrase constraints;
5. ambiguity registry referential integrity;
6. English surface ownership checks;
7. near-duplicate review with no unresolved merge-class candidates;
8. repository CI;
9. Retrieval Quality Gate;
10. Retrieval Storage Final Benchmark;
11. Production Image Build.

Until those gates complete successfully, the 4,384-concept state is implemented but provisional.

## Next expansion

After Batch B is green and consolidated, continue Batch C (+100 each) for these four domains. Do not start multilingual transfer until the English canonical IDs and ambiguity decisions are stable.
