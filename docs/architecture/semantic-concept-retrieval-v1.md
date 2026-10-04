# Semantic concept retrieval v1

## Goal

Promote canonical semantic concepts from reranking metadata into a bounded
retrieval candidate channel without replacing vector or lexical recall.

## Pipeline

```text
query
  -> SemanticQueryAnalyzer
  -> canonical concept IDs
  -> CONCEPT retrieval
  -> published SearchProjection metadata
  -> RRF with VECTOR / LEXICAL / REFERENCE
```

Concept retrieval is language-independent. A concept recognized in an English
query may retrieve a Russian, Kazakh, Chinese, German, French, Spanish,
Portuguese, Italian, Turkish or Greek chunk when both resolve to the same
canonical concept ID.

## Safety contract

- access-level routing remains mandatory;
- only the published generation is eligible;
- retention status must be ACTIVE;
- identifier-scoped queries keep the concept channel inside the resolved
  document scope;
- semantic analysis failure returns no concept candidates and does not fail the
  request;
- at most four query concepts and four concept candidates enter the channel;
- semantic concepts never hard-filter vector or lexical retrieval;
- the concept channel is non-critical for retrieval availability.

## Storage

Chunk annotations already persist `semanticConcepts` in
`metadata_json`. Each access-level/language leaf receives a GIN expression
index over that array. The lookup is cross-language but remains partition-pruned
by access level.

## Measurement

The existing semantic golden set remains the recognition gate:

- 120 cases;
- 40 EN;
- 40 RU;
- 40 KK;
- all 16 root semantic domains.

The next evaluation increment should compare baseline and concept-channel
Recall@K / MRR on a retrieval golden set where relevant chunks are known, not
only concept recognition.
