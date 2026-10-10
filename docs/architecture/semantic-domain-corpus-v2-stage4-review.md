# Semantic Domain Corpus v2 — Stage 4 review

Status: TARGET / review record for `feature/semantic-domain-corpus-v2`.

## Scope

Stage 4 now contains three balanced +100 rounds for each of the four final English-first domains:

- `mathematics_statistics`: 24 baseline + 300 = 324;
- `physics_astronomy`: 24 baseline + 300 = 324;
- `earth_environmental_science`: 24 baseline + 300 = 324;
- `psychology_sociology`: 24 baseline + 300 = 324.

The branch target is now 6,384 English concepts: 384 baseline concepts plus 6,000 additions.

## Distribution

Each Stage 4 domain keeps its existing four-subdomain taxonomy. Batch A, Batch B and Batch C each contribute 25 new canonical multi-word concepts to every existing subdomain. Each Stage 4 subdomain therefore contains 81 concepts total: 6 baseline + 75 v2 additions.

No parallel taxonomy is introduced.

## Coverage emphasis

### Mathematics / statistics

Batch C adds asymptotic and missing-data statistics, deeper algebra/geometry, duality/decomposition/stochastic optimization and advanced integration/eigensolver/error-estimation methods.

Representative additions include `martingale convergence theorem`, `restricted maximum likelihood`, `Ricci curvature tensor`, `Wasserstein ambiguity set`, `Arnoldi iteration method`, and `a posteriori error estimate`.

### Physics / astronomy

Batch C adds nonlinear and continuum mechanics, critical/statistical thermodynamics, advanced electromagnetic materials/wave phenomena and observational/relativistic astrophysics.

Representative additions include `Kolmogorov Arnold Moser theory`, `Green Kubo relation`, `Maxwell stress tensor`, `surface plasmon resonance`, `weak lensing tomography`, and `standard siren distance`.

### Earth / environmental science

Batch C adds geodynamics and seismic imaging, climate diagnostics and feedbacks, surface-groundwater process modeling and quantitative exposure/remediation assessment.

Representative additions include `seismic tomography model`, `ice sheet mass balance`, `hydrologic forecast uncertainty`, `environmental risk characterization`, and `monitored natural attenuation`.

### Psychology / sociology

Batch C adds memory and judgment mechanisms, functional/learning interventions, intergroup and persuasion mechanisms and advanced survey/network/longitudinal methodology.

Representative additions include `encoding specificity principle`, `functional behavior assessment`, `ingroup favoritism effect`, `multilevel regression poststratification`, and `peer influence estimation`.

## Gate-driven corpus remediation

The uniqueness gate remains fail-fast and was not weakened.

Before and during Stage 4, CI/retrieval-quality diagnostics exposed historical canonical collisions that had accumulated across earlier supplements. These are treated as corpus defects, not as reasons to relax validation.

Resolved examples include:

- Finance Batch B/C exact repeats, beginning with `loan amortization schedule`;
- Medicine baseline/D collision `therapeutic drug monitoring` → `therapeutic concentration monitoring`;
- Energy B/D collision `startup fuel consumption` → `startup energy requirement` in Batch D;
- Energy B/D collision `equivalent forced outage` → `forced outage performance metric` in Batch D;
- Mathematics/Manufacturing collision `model predictive control` → `receding horizon optimization` in Mathematics Batch B;
- Physics/Chemistry collision `free energy perturbation` → `Zwanzig free energy relation` in Physics Batch B.

The policy remains: repair canonical data while preserving quota and semantic intent; never bypass global uniqueness.

## Quality rules

Stage 4 uses the existing production guardrails unchanged:

1. canonical phrases contain 2–6 words;
2. normalized canonical phrases are globally unique;
3. IDs remain language-independent and domain/subdomain-derived;
4. domain/subdomain ownership must exist in the authoritative taxonomy;
5. English preferred and alias surfaces retain single ownership;
6. ambiguity entries must reference real canonical IDs;
7. near-duplicate review remains advisory, while merge-class duplicates require explicit remediation.

Batch C is intentionally biased toward deeper methods, mechanisms and specialist terminology rather than lexical variants of Batch A/B. Count growth alone is not treated as acceptance.

## Release gates

Stage 4 Batch A+B+C is admissible only when the current branch head passes:

1. expected 6,384 total concepts;
2. 324 concepts per Stage 4 domain and 81 per Stage 4 subdomain;
3. canonical normalized uniqueness;
4. multi-word phrase constraints;
5. ambiguity registry referential integrity;
6. English surface ownership checks;
7. near-duplicate review;
8. repository CI;
9. Retrieval Quality Gate;
10. Retrieval Storage Final Benchmark;
11. Production Image Build.

Until those gates complete successfully, the 6,384-concept state is implemented but provisional.

## Next expansion

After Batch C is green and consolidated, complete Stage 4 with Batch D (+100 each), taking all sixteen domains to the +400 target and the English corpus to 6,784 total concepts. After that, stop phrase expansion and perform English consolidation before RU → KK language transfer.
