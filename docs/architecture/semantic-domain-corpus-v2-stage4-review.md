# Semantic Domain Corpus v2 — Stage 4 review

Status: TARGET / review record for `feature/semantic-domain-corpus-v2`.

## Scope

Stage 4 starts the final English-first domain group with one balanced +100 round for each remaining baseline-only domain:

- `mathematics_statistics`: 24 baseline + 100 = 124;
- `physics_astronomy`: 24 baseline + 100 = 124;
- `earth_environmental_science`: 24 baseline + 100 = 124;
- `psychology_sociology`: 24 baseline + 100 = 124.

The branch target becomes 5,584 English concepts: 384 baseline concepts plus 5,200 additions.

## Distribution

Each Stage 4 domain keeps its existing four-subdomain taxonomy. Batch A contributes 25 new canonical multi-word concepts to each existing subdomain. No parallel taxonomy is introduced.

## Coverage emphasis

### Mathematics / statistics

Probability and statistical estimation, algebra and geometry, constrained/robust optimization and numerical analysis. Representative additions include `bootstrap resampling method`, `singular value decomposition`, `distributionally robust optimization`, and `Krylov subspace method`.

### Physics / astronomy

Analytical mechanics, thermodynamics/statistical mechanics, electromagnetism and astrophysics. Representative additions include `Hamiltonian mechanics formulation`, `grand canonical ensemble`, `electromagnetic scattering cross section`, and `gravitational lensing analysis`.

### Earth / environmental science

Geology and geophysics, climate dynamics, hydrology and environmental assessment. Representative additions include `seismic hazard assessment`, `climate sensitivity estimate`, `groundwater recharge estimation`, and `cumulative impact assessment`.

### Psychology / sociology

Cognitive processes, learning and behavior, social psychology and empirical sociology methods. Representative additions include `executive function assessment`, `delay discounting behavior`, `group polarization effect`, and `respondent driven sampling`.

## Pre-Stage-4 quality remediation

The Stage 3 Retrieval Quality Gate exposed an older corpus-construction defect rather than a retrieval metric regression. Production catalog construction failed on normalized duplicate canonical phrases, starting with `loan amortization schedule` in Finance Batch B/C.

The uniqueness gate was retained unchanged. Before Stage 4 integration:

- Finance Batch C exact repeats against Batch B were replaced with distinct professional concepts;
- the Medicine Batch D phrase `therapeutic drug monitoring`, already present in the baseline, was replaced by `therapeutic concentration monitoring`;
- tests were updated to reference the surviving canonical IDs.

This confirms the intended failure model: exact canonical collisions are release-blocking and must be repaired in corpus data rather than bypassed in the loader.

## Quality rules

Stage 4 uses the existing production guardrails unchanged:

1. canonical phrases contain 2–6 words;
2. normalized canonical phrases are globally unique;
3. IDs remain language-independent and domain/subdomain-derived;
4. domain/subdomain ownership must exist in the authoritative taxonomy;
5. English preferred and alias surfaces retain single ownership;
6. ambiguity entries must reference real canonical IDs;
7. near-duplicate review remains advisory but unresolved merge-class candidates block acceptance.

## Release gates

Stage 4 Batch A is admissible only when the current branch head passes:

1. expected 5,584 total concepts;
2. 124 concepts per Stage 4 domain and 31 per Stage 4 subdomain;
3. canonical normalized uniqueness;
4. multi-word phrase constraints;
5. ambiguity registry referential integrity;
6. English surface ownership checks;
7. near-duplicate review;
8. repository CI;
9. Retrieval Quality Gate;
10. Retrieval Storage Final Benchmark;
11. Production Image Build.

Until those gates complete successfully, the 5,584-concept state is implemented but provisional.

## Next expansion

After Batch A is consolidated, continue balanced Batch B (+100 each) across the same four domains. Do not add Batch E to any of the twelve already completed domains.
