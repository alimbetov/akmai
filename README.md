# akmai

Local multilingual RAG on Spring Boot + Spring AI + Ollama + PostgreSQL/pgvector.

## Goal

Akmai is a local-first RAG foundation for multilingual knowledge bases with a focus on:

- Kazakh
- Russian
- English
- Chinese
- legal documents
- medical documents
- technical documentation

Default models:

- embeddings: `qwen3-embedding:0.6b`
- generation: `qwen3:8b`

The chunking pipeline is intentionally independent from a concrete embedding model so the same canonical chunks can later be re-embedded with `qwen3-embedding:4b` or `qwen3-embedding:8b`.

## Architecture

```text
TEXT
  |
  v
TextNormalizer
  |
  v
StructuralUnitExtractor
  |
  v
DomainSemanticClassifier
  |
  v
AtomicUnitProtector
  |
  v
SemanticChunker
  |
  +--> CrossReferenceExtractor
  |
  +--> EmbeddingTextBuilder
  |
  v
KnowledgeChunk
  |
  v
VectorStore
  |
  v
qwen3-embedding
  |
  v
PostgreSQL + pgvector
```

Question path:

```text
QUESTION
  |
  v
qwen3-embedding
  |
  v
pgvector similarity search
  |
  v
TOP 5 chunks
  |
  v
qwen3:8b
  |
  v
ANSWER + SOURCES
```

## Semantic chunking rules

The first implementation protects meaning before token size.

Examples:

- legal rule + exception stay together when possible;
- legal obligation/prohibition/right markers are classified;
- medical indication + dosage can stay together;
- dosage + contraindication can stay together;
- section boundaries are treated as preferred chunk boundaries;
- cross references such as article/section/clause references are extracted;
- embedding text is enriched with document title, domain, language and section path.

Current token policy:

```text
target      750
soft max   1200
hard max   1800
minimum    250
```

These values are configurable under `akmai.chunking`.

## Local infrastructure

Start PostgreSQL + pgvector:

```bash
docker compose up -d postgres
```

Install Ollama models:

```bash
ollama pull qwen3-embedding:0.6b
ollama pull qwen3:8b
```

Run the application:

```bash
mvn spring-boot:run
```

## Ingest text

```http
POST /api/knowledge/text
Content-Type: application/json
```

Example:

```json
{
  "documentId": "law-001",
  "title": "Bank service agreement",
  "text": "Статья 25. Расторжение договора ...",
  "source": "agreement.md",
  "language": "ru",
  "domain": "LEGAL",
  "metadata": {
    "version": "2026-01"
  }
}
```

Response:

```json
{
  "documentId": "law-001",
  "chunkCount": 4
}
```

## Ask a question

```http
POST /api/rag/ask
Content-Type: application/json
```

```json
{
  "question": "Когда банк вправе расторгнуть договор?"
}
```

The generation prompt requires answers to remain grounded in retrieved context and asks the model not to omit legal/medical conditions, exceptions, contraindications or restrictions.

## Re-embedding strategy

Chunking is separated from embedding model selection.

Target evolution:

```text
canonical KnowledgeChunk
        |
        +--> qwen3-embedding:0.6b
        |
        +--> qwen3-embedding:4b
        |
        +--> qwen3-embedding:8b
```

A future persistence layer should store canonical documents, chunks, relations and embedding-version metadata separately from the pgvector retrieval projection. That will allow model upgrades without reparsing the original source documents.

## Next milestones

1. canonical PostgreSQL document/chunk tables
2. embedding model/version registry
3. re-embedding job
4. parent-child chunk hierarchy
5. reference expansion during retrieval
6. PDF/DOCX/Markdown parsers
7. multilingual KK/RU/EN/ZH retrieval benchmark
8. hybrid search and reranking
9. retrieval evaluation
