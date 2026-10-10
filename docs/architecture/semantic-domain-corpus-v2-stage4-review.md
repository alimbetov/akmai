# Semantic Domain Corpus v2 — Stage 4 review

Status: TARGET / review record for `feature/semantic-domain-corpus-v2`.

## Scope

Stage 4 now contains two balanced +100 rounds for each of the four final English-first domains:

- `mathematics_statistics`: 24 baseline + 200 = 224;
- `physics_astronomy`: 24 baseline + 200 = 224;
- `earth_environmental_science`: 24 baseline + 200 = 224;
- `psychology_sociology`: 24 baseline + 200 = 224.

The branch target is now 5,984 English concepts: 384 baseline concepts plus 5,600 additions.

## Distribution

Each Stage 4 domain keeps its existing four-subdomain taxonomy. Batch A and Batch B each contribute 25 new canonical multi-word concepts to every existing subdomain, so each Stage 4 subdomain now contains 56 concepts total: 6 baseline + 50 v2 additions.

No parallel taxonomy is introduced.

## Coverage emphasis

### Mathematics / statistics

Batch A established probability/statistical estimation, algebra/geometry, constrained optimization and numerical analysis. Batch B deepens Bayesian and causal methods, algebraic/topological structures, modern optimization algorithms and numerical linear/PDE methods.

Representative Batch B additions include `Bayesian hierarchical model`, `canonical correlation analysis`, `Fenchel duality relation`, `bilevel optimization problem`, `adaptive mesh refinement`, and `adjoint sensitivity method`.

### Physics / astronomy

Batch A established analytical mechanics, thermodynamics/statistical mechanics, electromagnetism and astrophysics. Batch B adds constrained mechanics, statistical distributions and irreversible thermodynamics, field/gauge methods and advanced stellar/cosmological concepts.

Representative Batch B additions include `Poisson bracket formulation`, `Onsager reciprocal relation`, `electromagnetic reciprocity theorem`, `stellar population synthesis`, and `gravitational wave background`.

### Earth / environmental science

Batch A established geology/geophysics, climate dynamics, hydrology and environmental assessment. Batch B adds basin and structural analysis, climate feedback/pathway concepts, groundwater/flood modeling and environmental due-diligence/restoration methods.

Representative Batch B additions include `sequence stratigraphy framework`, `equilibrium climate sensitivity`, `groundwater vulnerability mapping`, `source apportionment analysis`, and `environmental due diligence`.

### Psychology / sociology

Batch A established cognition, learning/behavior, social psychology and empirical sociology methods. Batch B deepens perception/metacognition, reinforcement schedules and conditioning, group/interpersonal mechanisms and quantitative/longitudinal sociology methodology.

Representative Batch B additions include `attentional blink effect`, `signal detection sensitivity`, `behavioral momentum theory`, `cognitive dissonance effect`, `structural equation modeling`, and `event history analysis`.

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

Batch B was intentionally biased toward materially deeper methods and domain concepts rather than lexical variants of Batch A. Count growth alone is not treated as acceptance.

## Release gates

Stage 4 Batch A+B is admissible only when the current branch head passes:

1. expected 5,984 total concepts;
2. 224 concepts per Stage 4 domain and 56 per Stage 4 subdomain;
3. canonical normalized uniqueness;
4. multi-word phrase constraints;
5. ambiguity registry referential integrity;
6. English surface ownership checks;
7. near-duplicate review;
8. repository CI;
9. Retrieval Quality Gate;
10. Retrieval Storage Final Benchmark;
11. Production Image Build.

Until those gates complete successfully, the 5,984-concept state is implemented but provisional.

## Next expansion

After Batch B is green and consolidated, continue balanced Batch C (+100 each) across the same four domains, taking them to 324 concepts each. Do not add Batch E to any of the twelve already completed domains.
