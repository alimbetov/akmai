#!/usr/bin/env python3
"""Materialize the controlled rag-benchmark-v1 corpus.

The language schedule is deliberately decorrelated from query-class rotation so
identifier-only EN/RU cases cannot starve any other supported language slice.
Surface forms and chunk-identity functions live in generate_controlled_corpus.py.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import generate_controlled_corpus as base


def build() -> tuple[list[dict], list[dict]]:
    documents: list[dict] = []
    queries: list[dict] = []
    class_count = len(base.ANSWERABLE_CLASSES)
    language_count = len(base.LANGUAGES)

    for index in range(base.ANSWERABLE_COUNT):
        query_class = base.ANSWERABLE_CLASSES[index % class_count]
        domain = base.DOMAINS[index % len(base.DOMAINS)]
        lang = base.LANGUAGES[
            (index + index // class_count) % language_count
        ]
        if query_class in {"IDENTIFIER_ONLY", "IDENTIFIER_SEMANTIC"}:
            domain = "LEGAL"
            lang = "en" if index % 2 == 0 else "ru"

        entity = f"AkmEntity{index + 1:03d}"
        document_id = f"bench-doc-{index + 1:03d}"
        title = f"AkmAI {domain.title()} Fixture {index + 1:03d}"
        doc_lang = lang

        if query_class in {"IDENTIFIER_ONLY", "IDENTIFIER_SEMANTIC"}:
            identifier = f"KZ-2026-{100000 + index:06d}"
            text = base.identifier_document(lang, identifier, index)
            if query_class == "IDENTIFIER_ONLY":
                qtext = (
                    f"договор {identifier}"
                    if lang == "ru"
                    else f"contract {identifier}"
                )
            else:
                qtext = (
                    f"Какой штраф установлен по договору {identifier}?"
                    if lang == "ru"
                    else f"What penalty applies under contract {identifier}?"
                )
        else:
            if query_class == "CROSS_LANGUAGE":
                doc_lang = "ru" if lang == "en" else "en"
            text = base.document_text(
                doc_lang,
                domain,
                entity,
                index,
                query_class,
            )
            qtext = base.question(lang, domain, entity, index, query_class)

        chunk_id = base.child_chunk_id(document_id, title, text)
        documents.append(
            {
                "id": document_id,
                "title": title,
                "text": text,
                "source": f"benchmark://controlled/{document_id}",
                "language": doc_lang,
                "domain": domain,
                "accessLevel": 1,
                "metadata": {
                    "corpusKind": "CONTROLLED_SYNTHETIC",
                    "scenarioId": f"scenario-{index + 1:03d}",
                    "expectedSearchableChunkId": chunk_id,
                },
            }
        )
        queries.append(
            {
                "id": f"q-{index + 1:03d}",
                "language": lang,
                "domain": domain,
                "queryClass": query_class,
                "difficulty": base.DIFFICULTIES[
                    index % len(base.DIFFICULTIES)
                ],
                "question": qtext,
                "answerable": True,
                "relevantDocumentIds": [document_id],
                "relevantChunkIds": [chunk_id],
                "forbiddenChunkIds": [],
            }
        )

    for offset in range(base.UNANSWERABLE_COUNT):
        index = base.ANSWERABLE_COUNT + offset
        lang = base.LANGUAGES[offset % language_count]
        domain = base.DOMAINS[offset % len(base.DOMAINS)]
        _, primary, _, _, _ = base.TERMS[lang][domain]
        entity = f"MissingEntity{offset + 1:03d}"
        qtext = base.LANG[lang]["missing"].format(
            entity=entity,
            label=primary,
        )
        queries.append(
            {
                "id": f"q-{index + 1:03d}",
                "language": lang,
                "domain": domain,
                "queryClass": "UNANSWERABLE",
                "difficulty": base.DIFFICULTIES[
                    index % len(base.DIFFICULTIES)
                ],
                "question": qtext,
                "answerable": False,
                "relevantDocumentIds": [],
                "relevantChunkIds": [],
                "forbiddenChunkIds": [],
            }
        )

    base.validate(documents, queries)
    return documents, queries


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--out",
        default="benchmarks/rag-benchmark-v1/release",
        help="Output dataset root",
    )
    args = parser.parse_args()
    root = Path(args.out)
    root.mkdir(parents=True, exist_ok=True)

    documents, queries = build()
    manifest = {
        "benchmarkVersion": base.BENCHMARK_VERSION,
        "corpusVersion": base.CORPUS_VERSION,
        "releaseQualified": True,
        "metadata": {
            "owner": "akmai-quality",
            "annotationPolicy": "deterministic-controlled-fixture-v1",
            "corpusKind": "CONTROLLED_SYNTHETIC",
            "realWorldQualityClaim": "false",
            "generator": "materialize_controlled_corpus.py",
            "queryCount": str(len(queries)),
            "documentCount": str(len(documents)),
        },
    }
    (root / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    base.write_jsonl(root / "documents.jsonl", documents)
    base.write_jsonl(root / "queries.jsonl", queries)

    print(
        json.dumps(
            {
                "root": str(root),
                "documents": len(documents),
                "queries": len(queries),
                "unanswerable": base.UNANSWERABLE_COUNT,
                "corpusVersion": base.CORPUS_VERSION,
            },
            sort_keys=True,
        )
    )


if __name__ == "__main__":
    main()
