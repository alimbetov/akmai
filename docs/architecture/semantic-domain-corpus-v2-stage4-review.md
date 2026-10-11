# Semantic Domain Corpus v2 — Stage 4 review

Status: TARGET / final English expansion review record for `feature/semantic-domain-corpus-v2`.

## Scope

Stage 4 is implemented with four balanced +100 rounds for each of the four final English-first domains:

- `mathematics_statistics`: 24 baseline + 400 = 424;
- `physics_astronomy`: 24 baseline + 400 = 424;
- `earth_environmental_science`: 24 baseline + 400 = 424;
- `psychology_sociology`: 24 baseline + 400 = 424.

All sixteen English domains now target the same completed level: **424 concepts per domain**. The combined English corpus target is **6,784 concepts**: 384 baseline concepts plus 6,400 v2 additions.

## Distribution

Every domain retains its existing four-subdomain taxonomy. Across the complete English v2 corpus:

- 16 domains × 424 concepts = 6,784 concepts;
- 64 subdomains × 106 concepts = 6,784 concepts;
- each subdomain contains 6 baseline concepts + 100 v2 concepts;
- every A/B/C/D batch contributes exactly 25 new canonical concepts per subdomain in its domain.

No parallel taxonomy is introduced.

## Batch D coverage

### Mathematics / statistics

Batch D deepens probability/statistical inference, algebraic structures, constrained/variational optimization and numerical discretization/verification.

Representative additions include `Bayesian model averaging`, `semiparametric efficiency bound`, `Smith normal form`, `duality gap certificate`, `discontinuous Galerkin method`, and `manufactured solution verification`.

### Physics / astronomy

Batch D deepens nonlinear mechanics, nonequilibrium/statistical thermodynamics, wave/scattering electromagnetics and observational/multimessenger astrophysics.

Representative additions include `Hamiltonian chaos indicator`, `Jarzynski equality relation`, `Mie scattering solution`, `neutron star tidal deformability`, `binary inspiral waveform`, and `exoplanet atmospheric retrieval`.

### Earth / environmental science

Batch D expands tectonics/petrology, climate feedback and attribution, groundwater/surface-water process analysis and quantitative remediation/ecological assessment.

Representative additions include `induced seismicity assessment`, `remaining carbon budget`, `managed aquifer recharge`, `species sensitivity distribution`, and `natural capital accounting`.

### Psychology / sociology

Batch D adds attention/memory mechanisms, applied behavioral procedures, social identity/justice mechanisms and advanced survey/panel/causal methodology.

Representative additions include `retrieval induced forgetting`, `functional communication training`, `stereotype content model`, `small area estimation`, and `marginal structural model`.

## Gate-driven corpus remediation

The global normalized uniqueness gate remains fail-fast and was not weakened.

The pre-Batch-D Stage 4 run exposed an Energy canonical collision around `equivalent forced outage`. The retained Energy Batch B canonical was made more precise as `equivalent forced outage factor`; this preserves the professional concept and removes the ambiguous duplicate surface without reducing quota.

Earlier remediation remains part of the quality record, including Finance exact repeats, Medicine baseline/D collision repair, Stage 2 cross-batch duplicates and cross-domain canonical collisions discovered while expanding Stage 3/4.

Policy: repair canonical data while preserving semantic intent and batch balance; never bypass global uniqueness.

## Quality rules

Stage 4 uses the production guardrails unchanged:

1. canonical phrases contain 2–6 words;
2. normalized canonical phrases are globally unique;
3. IDs remain language-independent and domain/subdomain-derived;
4. domain/subdomain ownership must exist in the authoritative taxonomy;
5. English preferred and alias surfaces retain single ownership;
6. ambiguity entries reference real canonical IDs;
7. near-duplicate review remains advisory and never auto-merges concepts;
8. exact or merge-class duplicates are release blockers and require explicit remediation.

The ambiguity registry remains a corpus-quality metadata mechanism; it does not imply query-time contextual disambiguation in `SemanticConceptMatcher`.

## Final English expansion gates

The 6,784-concept state is considered accepted only when the current branch head passes:

1. expected 6,784 total concepts;
2. exactly 424 concepts in each of 16 domains;
3. exactly 106 concepts in each of 64 subdomains;
4. canonical normalized uniqueness;
5. 2–6 word canonical phrase constraints;
6. ambiguity registry referential integrity;
7. English surface ownership checks;
8. near-duplicate review with no unresolved merge-class defect;
9. repository CI;
10. Retrieval Quality Gate;
11. Retrieval Storage Final Benchmark;
12. Production Image Build.

Until these gates complete successfully on the final Batch D head, the corpus is **implemented at 6,784 but provisional**.

## Next phase

Do not add Batch E or increase per-domain quota. After final gates are green, move to **Stage 5 — English consolidation**:

- disposition remaining near-duplicate candidates;
- remove weak/synthetic concepts if any and replace them one-for-one where necessary;
- freeze stable English canonical IDs;
- capture retrieval-quality evidence;
- then begin Stage 6 language transfer in order **RU → KK → remaining supported languages**.
