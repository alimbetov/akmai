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


## Business identifier index

Akmai extracts exact business identifiers independently from semantic embeddings.

Supported initial types include:

- CONTRACT_NUMBER
- DOCUMENT_NUMBER
- ORDER_NUMBER
- INVOICE_NUMBER
- APPLICATION_NUMBER
- CASE_NUMBER
- CLAIM_NUMBER
- PAYMENT_NUMBER
- PROTOCOL_NUMBER
- LETTER_NUMBER
- DOCUMENT_ID

The canonical model is:

```text
DocumentPage -> SemanticChunk -> IdentifierExtractor
                              -> DocumentIdentifier
                              -> PostgreSQL source of truth
                              -> IdentifierSearchIndex
```

Each identifier is addressed by `documentId + chunkId + pageNumber`. Values are normalized for exact lookup, while the raw value and surrounding context are retained.

`document_identifier` is range-partitioned by `created_at`. Partition creation is kept out of the ingestion hot path: a scheduler prepares the current and next two monthly partitions, while a DEFAULT partition provides a safety fallback.

The search layer is intentionally abstracted behind `IdentifierSearchIndex`. PostgreSQL is the initial implementation. A future local index such as Lucene can be rebuilt from the canonical PostgreSQL table without changing ingestion or the domain model.

Target mixed-query flow:

```text
"Какие штрафы в договоре KZ-2026-001847?"
          |
          +--> exact identifier lookup -> documentId
          |
          +--> semantic query "Какие штрафы?"
                         |
                         v
                 pgvector filtered by documentId
```


## AKMAI parallel pipeline

```text
             WRITE                       READ
               |                           |
               v                           v
            Document                    Question
               |                           |
               v                           v
        SemanticChunker              QueryChunker
               |                           |
               v                           v
       KnowledgeChunk[]               QueryChunk[]
               |                           |
               v                           v
 ParallelIngestionExecutor     ParallelRetrievalExecutor
               |                           |
       +-------+-------+           +-------+-------+
       v       v       v           v       v       v
      IDs     refs   vectors       IDs    vector lexical
       |       |       |           |       |       |
       +-------+-------+           +-------+-------+
               |                           |
               v                           v
     PersistenceCoordinator           ResultFusion
               |                           |
               v                           v
        Search projections             Reranker
                                           |
                                           v
                                    ContextAssembler
                                           |
                                           v
                                          Qwen
```

The same `IdentifierExtractor` and `IdentifierParser[]` rules are shared by WRITE and READ. Document chunks are enriched concurrently before persistence; questions are decomposed into independent query chunks and applicable retrieval strategies are executed concurrently. Both executors use bounded configurable thread pools rather than unbounded `parallelStream()`.

Current implementations cover identifier and vector retrieval. Lexical/reference retrieval, a learned reranker and a local search index are explicit extension points rather than being coupled to `RagQuestionService`.
