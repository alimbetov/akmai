# Semantic Domain Corpus v2 — Stage 3 review

Status: TARGET / review record for `feature/semantic-domain-corpus-v2`.

## Scope

Stage 3 starts the English-first science and technology expansion with Batch A (+100 per domain):

- `computer_science_ai`: 24 baseline + 100 = 124;
- `medicine_pharmacology`: 24 baseline + 100 = 124;
- `biology_genetics`: 24 baseline + 100 = 124;
- `chemistry_materials`: 24 baseline + 100 = 124.

The branch target becomes 3,984 English concepts: 384 baseline concepts plus 3,600 additions.

## Distribution

Each Stage 3 domain keeps the existing taxonomy and receives 25 new canonical multi-word phrases in each of its four existing subdomains. No new parallel subdomain taxonomy is introduced.

## Coverage emphasis

### Computer science / AI

Algorithms and data structures, distributed/software systems, machine learning, retrieval/NLP and LLM operations. Representative additions include `amortized complexity analysis`, `distributed consensus protocol`, `model drift detection`, and `retrieval augmented generation`.

### Medicine / pharmacology

Clinical diagnosis, pharmacokinetics/pharmacodynamics, clinical-trial design and epidemiology. Representative additions include `diagnostic test specificity`, `receptor occupancy model`, `adaptive trial design`, and `vaccine effectiveness estimate`.

### Biology / genetics

Molecular regulation, population and medical genetics, cell biology and physiology. Representative additions include `chromatin accessibility analysis`, `polygenic risk score`, `mitochondrial membrane potential`, and `glomerular filtration rate`.

### Chemistry / materials

Organic synthesis, analytical chemistry, physical chemistry and materials characterization. Representative additions include `retrosynthetic analysis method`, `analytical method validation`, `electrochemical impedance spectroscopy`, and `fracture toughness testing`.

## Quality rules

Stage 3 uses the existing production guardrails unchanged:

1. 2–6 canonical words;
2. global normalized canonical uniqueness;
3. stable language-independent concept IDs;
4. valid domain/subdomain ownership;
5. English preferred/alias surface ownership;
6. ambiguity registry referential integrity;
7. advisory near-duplicate review without automatic merge.

A terminology cleanup changed the epidemiology phrase `disability adjusted life` to the canonical `disability adjusted life years` before integration.

## Release gates

Stage 3 Batch A is admissible only when the current branch head passes:

1. expected 3,984 total concepts;
2. 124 concepts per Stage 3 domain and 31 per Stage 3 subdomain;
3. canonical normalized uniqueness;
4. multi-word phrase constraints;
5. ambiguity registry referential integrity;
6. English surface ownership checks;
7. repository CI;
8. Retrieval Quality Gate;
9. Retrieval Storage Final Benchmark;
10. Production Image Build.

Until those gates complete successfully, the 3,984-concept state is implemented but provisional.

## Next expansion

After Batch A is green and consolidated, continue Batch B (+100 each) for these four domains before starting the remaining quantitative/science/social domains.
