# RAG assurance and multilingual domain enrichment

## Objective

This phase hardens the answer boundary of AkmAI without replacing the existing
hybrid retrieval, ACL, lifecycle, or Adaptive Graph architecture.

The design adds two independent safety fences:

```text
retrieval / graph / rerank
        |
        v
TemporalAuthorityFilter
        |
        v
ContextBudget + published revalidation
        |
        v
LLM generation
        |
        v
CitationValidator
        |
        v
AnswerGroundingVerifier
        |
        v
response / learning
```

Learning is intentionally downstream of grounding. Unsupported generated output
must never become positive Adaptive Graph evidence.

## Deterministic grounding contract

The first grounding stage is deliberately deterministic and fail-closed.

Every factual sentence must:

1. contain at least one valid `[SOURCE n]` marker;
2. cite a source that exists in the bounded final context;
3. when the sentence contains numeric literals, every numeric literal must also
   occur in at least one source cited by that sentence.

Examples rejected by the guard:

```text
The dose is 10 mg.
```

when no source is cited, and:

```text
The dose is 20 mg [SOURCE 1].
```

when SOURCE 1 states 10 mg.

This guard is not a semantic entailment/NLI model. It is the deterministic
minimum contract that catches uncited claims and high-risk numeric drift before
richer claim-level entailment is added.

## Temporal and authority metadata

Storage lifecycle state and semantic validity are different concerns.

A generation can be technically published while the represented regulation,
policy, clinical guidance, or procedure is no longer effective.

A chunk may therefore carry these optional metadata fields:

```text
documentStatus: ACTIVE | CURRENT | EFFECTIVE |
                DRAFT | REPEALED | SUPERSEDED | EXPIRED | WITHDRAWN

effectiveFrom: YYYY-MM-DD
effectiveTo:   YYYY-MM-DD
```

Backward compatibility rule:

- if none of these fields is present, the existing published-generation contract
  remains authoritative;
- once an explicit field is present, malformed or inactive values fail closed
  and the chunk cannot enter the final context.

The current implementation evaluates validity against the current UTC date.
Historical `asOf` query semantics remain a separate future extension.

## Industry/domain model

`KnowledgeDomain` remains intentionally small and stable. Industry expansion is
data-driven through YAML profiles and `IndustryDomain`.

Current specialized profiles:

```text
LEGAL
  civil_law

MEDICAL
  cardiology
  pharmacology

TECHNICAL
  software
  cybersecurity

FINANCIAL
  banking

GOVERNMENT
  public_administration
```

Every YAML profile must define a localized name for every supported production
language:

```text
kk ru en zh de fr es pt it tr el
```

New specialized profiles also carry high-precision language-local semantic
patterns. Profiles compose with the existing generic legal/medical/general
classifiers instead of replacing them.

## Quality gates

The PR quality gate explicitly executes:

- 11-language profile completeness;
- 11-language semantic enrichment rules;
- multilingual answer grounding;
- numeric hallucination rejection;
- temporal authority filtering;
- end-to-end RagQuestionService assurance;
- existing multilingual retrieval regression;
- PostgreSQL retrieval integration;
- vector routing/storage integration;
- Adaptive Graph replay/calibration.

No RAG assurance change is merge-ready until all exact-head workflows succeed.

## Next assurance phases

This phase intentionally does not pretend deterministic citation checks are a
complete semantic verifier.

The next layers should be introduced independently:

1. semantic claim/evidence entailment with calibrated abstention;
2. contradiction detection for structured values, negation, dates, dosage and
   normative status;
3. historical `asOf` temporal retrieval and jurisdiction/authority ranking;
4. corpus-backed answer-level golden sets with hard negatives;
5. paired statistical promotion gates for retrieval/graph policies;
6. cross-encoder reranking behind a controlled feature flag.
