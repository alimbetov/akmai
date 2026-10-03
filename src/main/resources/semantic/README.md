# AkmAI semantic resources

This directory contains the multilingual semantic lexicon used by query analysis.

## Contract

- Concepts are language-neutral IDs.
- Every concept maps to surface forms for the 11 supported languages.
- Industry dictionaries are enriched in ten controlled waves.
- Runtime loads resources into an immutable semantic index.
- A bounded frequency-aware cache stores query-analysis results only.
- Query frequency never changes semantic relevance weights.
- Vector retrieval always receives the original semantic query.
- Bounded synonym expansion is used only as a lexical retrieval signal.

See `docs/architecture/multilingual-semantic-lexicon-program.md` for the full
architecture and wave protocol.
