#!/usr/bin/env python3
"""Generate the versioned AkmAI controlled release-qualification corpus.

This corpus is intentionally synthetic and deterministic. It is designed to
exercise the real ingestion/retrieval/generation/grounding pipeline across all
supported languages, target domains and required query classes. It is a
regression/release-engineering gate, not a claim of real-world task accuracy.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import struct
import unicodedata
from collections import Counter
from pathlib import Path

BENCHMARK_VERSION = "rag-benchmark-v1"
CORPUS_VERSION = "controlled-synthetic-2026-10-v1"
LANGUAGES = ["kk", "ru", "en", "zh", "de", "fr", "es", "pt", "it", "tr", "el"]
DOMAINS = ["LEGAL", "MEDICAL", "TECHNICAL"]
ANSWERABLE_CLASSES = [
    "FACTUAL",
    "PARAPHRASE",
    "LEXICAL_EXACT",
    "IDENTIFIER_ONLY",
    "IDENTIFIER_SEMANTIC",
    "REFERENCE",
    "NUMERIC",
    "TEMPORAL",
    "MULTI_INTENT",
    "COMPARISON",
    "CROSS_LANGUAGE",
]
DIFFICULTIES = ["EASY", "MEDIUM", "HARD"]
ANSWERABLE_COUNT = 255
UNANSWERABLE_COUNT = 75

# Short language-native surface forms. Entity tokens remain stable Latin labels
# so CROSS_LANGUAGE cases have a language-independent named-entity anchor.
LANG = {
    "kk": {
        "doc": "{kind} {entity}: {primary} {a}{u1}; {secondary} {b} {u2}; {refword} {ref}; {dateword} {date}.",
        "what": "{entity} үшін {label} қандай?",
        "paraphrase": "{entity} үшін негізгі көрсетілген мән қанша?",
        "reference": "{refword} {ref} бойынша {entity} үшін {label} қандай?",
        "multi": "{entity} үшін {primary} және {secondary} қандай?",
        "compare": "{entity} үшін A нұсқасы мен B нұсқасын салыстырғанда қайсысының мәні төмен?",
        "date": "{entity} үшін {dateword} қашан?",
        "missing": "{entity} үшін {label} қандай?",
        "refword": "Бөлім",
        "dateword": "күшіне ену күні",
    },
    "ru": {
        "doc": "{kind} {entity}: {primary} {a}{u1}; {secondary} {b} {u2}; {refword} {ref}; {dateword} {date}.",
        "what": "Какое значение {label} установлено для {entity}?",
        "paraphrase": "Какой основной размер указан для {entity}?",
        "reference": "Согласно {refword} {ref}, какое значение {label} установлено для {entity}?",
        "multi": "Какие значения {primary} и {secondary} установлены для {entity}?",
        "compare": "Для {entity} какой вариант имеет меньшее значение: A или B?",
        "date": "Когда наступает {dateword} для {entity}?",
        "missing": "Какое значение {label} установлено для {entity}?",
        "refword": "Раздел",
        "dateword": "дата вступления",
    },
    "en": {
        "doc": "{kind} {entity}: {primary} {a}{u1}; {secondary} {b} {u2}; {refword} {ref}; {dateword} {date}.",
        "what": "What {label} applies to {entity}?",
        "paraphrase": "What main amount is specified for {entity}?",
        "reference": "According to {refword} {ref}, what {label} applies to {entity}?",
        "multi": "What are the {primary} and {secondary} for {entity}?",
        "compare": "For {entity}, which option has the lower value, A or B?",
        "date": "What is the {dateword} for {entity}?",
        "missing": "What {label} applies to {entity}?",
        "refword": "Section",
        "dateword": "effective date",
    },
    "zh": {
        "doc": "{kind} {entity}：{primary} {a}{u1}；{secondary} {b}{u2}；{refword} {ref}；{dateword} {date}。",
        "what": "{entity} 的{label}是多少？",
        "paraphrase": "{entity} 规定的主要数值是多少？",
        "reference": "根据{refword} {ref}，{entity} 的{label}是多少？",
        "multi": "{entity} 的{primary}和{secondary}分别是多少？",
        "compare": "{entity} 的 A 与 B 方案相比，哪个数值更低？",
        "date": "{entity} 的{dateword}是什么时候？",
        "missing": "{entity} 的{label}是多少？",
        "refword": "第",
        "dateword": "生效日期",
    },
    "de": {
        "doc": "{kind} {entity}: {primary} {a}{u1}; {secondary} {b} {u2}; {refword} {ref}; {dateword} {date}.",
        "what": "Welcher Wert für {label} gilt für {entity}?",
        "paraphrase": "Welcher Hauptwert ist für {entity} angegeben?",
        "reference": "Nach {refword} {ref}: Welcher Wert für {label} gilt für {entity}?",
        "multi": "Welche Werte für {primary} und {secondary} gelten für {entity}?",
        "compare": "Welche Option hat bei {entity} den niedrigeren Wert, A oder B?",
        "date": "Welches {dateword} gilt für {entity}?",
        "missing": "Welcher Wert für {label} gilt für {entity}?",
        "refword": "Abschnitt",
        "dateword": "Gültigkeitsdatum",
    },
    "fr": {
        "doc": "{kind} {entity} : {primary} {a}{u1} ; {secondary} {b} {u2} ; {refword} {ref} ; {dateword} {date}.",
        "what": "Quelle valeur de {label} s'applique à {entity} ?",
        "paraphrase": "Quel montant principal est indiqué pour {entity} ?",
        "reference": "Selon {refword} {ref}, quelle valeur de {label} s'applique à {entity} ?",
        "multi": "Quelles sont les valeurs de {primary} et {secondary} pour {entity} ?",
        "compare": "Pour {entity}, quelle option a la valeur la plus basse, A ou B ?",
        "date": "Quelle est la {dateword} pour {entity} ?",
        "missing": "Quelle valeur de {label} s'applique à {entity} ?",
        "refword": "Section",
        "dateword": "date d'entrée en vigueur",
    },
    "es": {
        "doc": "{kind} {entity}: {primary} {a}{u1}; {secondary} {b} {u2}; {refword} {ref}; {dateword} {date}.",
        "what": "¿Qué valor de {label} se aplica a {entity}?",
        "paraphrase": "¿Qué importe principal se especifica para {entity}?",
        "reference": "Según {refword} {ref}, ¿qué valor de {label} se aplica a {entity}?",
        "multi": "¿Cuáles son los valores de {primary} y {secondary} para {entity}?",
        "compare": "Para {entity}, ¿qué opción tiene el valor menor, A o B?",
        "date": "¿Cuál es la {dateword} de {entity}?",
        "missing": "¿Qué valor de {label} se aplica a {entity}?",
        "refword": "Sección",
        "dateword": "fecha de vigencia",
    },
    "pt": {
        "doc": "{kind} {entity}: {primary} {a}{u1}; {secondary} {b} {u2}; {refword} {ref}; {dateword} {date}.",
        "what": "Qual valor de {label} se aplica a {entity}?",
        "paraphrase": "Qual valor principal é especificado para {entity}?",
        "reference": "Segundo {refword} {ref}, qual valor de {label} se aplica a {entity}?",
        "multi": "Quais são os valores de {primary} e {secondary} para {entity}?",
        "compare": "Para {entity}, qual opção tem o valor menor, A ou B?",
        "date": "Qual é a {dateword} de {entity}?",
        "missing": "Qual valor de {label} se aplica a {entity}?",
        "refword": "Seção",
        "dateword": "data de vigência",
    },
    "it": {
        "doc": "{kind} {entity}: {primary} {a}{u1}; {secondary} {b} {u2}; {refword} {ref}; {dateword} {date}.",
        "what": "Quale valore di {label} si applica a {entity}?",
        "paraphrase": "Quale valore principale è specificato per {entity}?",
        "reference": "Secondo {refword} {ref}, quale valore di {label} si applica a {entity}?",
        "multi": "Quali sono i valori di {primary} e {secondary} per {entity}?",
        "compare": "Per {entity}, quale opzione ha il valore più basso, A o B?",
        "date": "Qual è la {dateword} di {entity}?",
        "missing": "Quale valore di {label} si applica a {entity}?",
        "refword": "Sezione",
        "dateword": "data di efficacia",
    },
    "tr": {
        "doc": "{kind} {entity}: {primary} {a}{u1}; {secondary} {b} {u2}; {refword} {ref}; {dateword} {date}.",
        "what": "{entity} için {label} değeri nedir?",
        "paraphrase": "{entity} için belirtilen ana değer nedir?",
        "reference": "{refword} {ref} uyarınca {entity} için {label} değeri nedir?",
        "multi": "{entity} için {primary} ve {secondary} değerleri nedir?",
        "compare": "{entity} için A ve B seçeneklerinden hangisinin değeri daha düşüktür?",
        "date": "{entity} için {dateword} nedir?",
        "missing": "{entity} için {label} değeri nedir?",
        "refword": "Bölüm",
        "dateword": "yürürlük tarihi",
    },
    "el": {
        "doc": "{kind} {entity}: {primary} {a}{u1}; {secondary} {b} {u2}; {refword} {ref}; {dateword} {date}.",
        "what": "Ποια τιμή {label} ισχύει για το {entity};",
        "paraphrase": "Ποιο κύριο ποσό ορίζεται για το {entity};",
        "reference": "Σύμφωνα με την {refword} {ref}, ποια τιμή {label} ισχύει για το {entity};",
        "multi": "Ποιες είναι οι τιμές {primary} και {secondary} για το {entity};",
        "compare": "Για το {entity}, ποια επιλογή έχει χαμηλότερη τιμή, A ή B;",
        "date": "Ποια είναι η {dateword} για το {entity};",
        "missing": "Ποια τιμή {label} ισχύει για το {entity};",
        "refword": "Ενότητα",
        "dateword": "ημερομηνία ισχύος",
    },
}

TERMS = {
    "kk": {"LEGAL": ("құқықтық ереже", "айыппұл", "мерзім", "%", "күн"), "MEDICAL": ("медициналық хаттама", "доза", "бақылау аралығы", "мг", "сағат"), "TECHNICAL": ("техникалық нұсқаулық", "таймаут", "қайта әрекет саны", "с", "рет")},
    "ru": {"LEGAL": ("правовое правило", "штраф", "срок", "%", "дней"), "MEDICAL": ("медицинский протокол", "доза", "интервал контроля", "мг", "часов"), "TECHNICAL": ("технический регламент", "таймаут", "число повторов", "с", "попыток")},
    "en": {"LEGAL": ("legal rule", "penalty", "deadline", "%", "days"), "MEDICAL": ("medical protocol", "dose", "monitoring interval", "mg", "hours"), "TECHNICAL": ("technical runbook", "timeout", "retry limit", "s", "attempts")},
    "zh": {"LEGAL": ("法律规则", "罚金", "期限", "%", "天"), "MEDICAL": ("医疗方案", "剂量", "监测间隔", "毫克", "小时"), "TECHNICAL": ("技术规程", "超时", "重试次数", "秒", "次")},
    "de": {"LEGAL": ("Rechtsregel", "Vertragsstrafe", "Frist", "%", "Tage"), "MEDICAL": ("medizinisches Protokoll", "Dosis", "Kontrollintervall", "mg", "Stunden"), "TECHNICAL": ("technisches Runbook", "Zeitlimit", "Wiederholungsgrenze", "s", "Versuche")},
    "fr": {"LEGAL": ("règle juridique", "pénalité", "délai", "%", "jours"), "MEDICAL": ("protocole médical", "dose", "intervalle de contrôle", "mg", "heures"), "TECHNICAL": ("procédure technique", "délai d'attente", "limite de tentatives", "s", "tentatives")},
    "es": {"LEGAL": ("regla jurídica", "penalización", "plazo", "%", "días"), "MEDICAL": ("protocolo médico", "dosis", "intervalo de control", "mg", "horas"), "TECHNICAL": ("procedimiento técnico", "tiempo de espera", "límite de reintentos", "s", "intentos")},
    "pt": {"LEGAL": ("regra jurídica", "penalidade", "prazo", "%", "dias"), "MEDICAL": ("protocolo médico", "dose", "intervalo de monitorização", "mg", "horas"), "TECHNICAL": ("procedimento técnico", "tempo limite", "limite de tentativas", "s", "tentativas")},
    "it": {"LEGAL": ("regola giuridica", "penale", "termine", "%", "giorni"), "MEDICAL": ("protocollo medico", "dose", "intervallo di monitoraggio", "mg", "ore"), "TECHNICAL": ("procedura tecnica", "timeout", "limite di tentativi", "s", "tentativi")},
    "tr": {"LEGAL": ("hukuk kuralı", "ceza", "süre", "%", "gün"), "MEDICAL": ("tıbbi protokol", "doz", "izlem aralığı", "mg", "saat"), "TECHNICAL": ("teknik çalışma kılavuzu", "zaman aşımı", "yeniden deneme sınırı", "s", "deneme")},
    "el": {"LEGAL": ("νομικός κανόνας", "ποινή", "προθεσμία", "%", "ημέρες"), "MEDICAL": ("ιατρικό πρωτόκολλο", "δόση", "διάστημα παρακολούθησης", "mg", "ώρες"), "TECHNICAL": ("τεχνικό εγχειρίδιο", "χρονικό όριο", "όριο επαναλήψεων", "s", "προσπάθειες")},
}


def normalize(text: str) -> str:
    text = unicodedata.normalize("NFC", text or "")
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    text = re.sub(r"[ \t]+", " ", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def _write_string(value: str) -> bytes:
    data = unicodedata.normalize("NFC", value or "").encode("utf-8")
    return struct.pack(">i", len(data)) + data


def parent_chunk_id(document_id: str, title: str, text: str) -> str:
    payload = b"\x02" + _write_string(document_id)
    payload += struct.pack(">q", 0)
    payload += _write_string(title) + _write_string(normalize(text))
    return "c2_" + hashlib.sha256(payload).hexdigest()


def child_chunk_id(document_id: str, title: str, text: str) -> str:
    parent = parent_chunk_id(document_id, title, text)
    payload = b"\x01" + _write_string("child")
    payload += _write_string(document_id) + _write_string(parent)
    payload += struct.pack(">i", 0)
    payload += _write_string(title) + _write_string(normalize(text))
    return "cc1_" + hashlib.sha256(payload).hexdigest()


def facts(index: int) -> tuple[int, int, int, str, int]:
    primary = 2 + (index * 7) % 31
    secondary = 3 + (index * 11) % 41
    compare_b = primary + 5 + (index % 7)
    month = 1 + (index % 12)
    day = 1 + (index * 3) % 27
    date = f"2027-{month:02d}-{day:02d}"
    reference = 10 + (index * 13) % 89
    return primary, secondary, compare_b, date, reference


def document_text(lang: str, domain: str, entity: str, index: int, query_class: str) -> str:
    kind, primary, secondary, u1, u2 = TERMS[lang][domain]
    a, b, compare_b, date, reference = facts(index)
    surface = LANG[lang]
    if query_class == "COMPARISON":
        # Keep one sentence so production parent/child chunk identity is stable.
        return normalize(
            surface["doc"].format(
                kind=kind,
                entity=entity,
                primary=f"{primary} A={a}{u1}, B={compare_b}{u1}; {primary}",
                a=a,
                u1=u1,
                secondary=secondary,
                b=b,
                u2=u2,
                refword=surface["refword"],
                ref=reference,
                dateword=surface["dateword"],
                date=date,
            )
        )
    return normalize(
        surface["doc"].format(
            kind=kind,
            entity=entity,
            primary=primary,
            a=a,
            u1=u1,
            secondary=secondary,
            b=b,
            u2=u2,
            refword=surface["refword"],
            ref=reference,
            dateword=surface["dateword"],
            date=date,
        )
    )


def identifier_document(lang: str, identifier: str, index: int) -> str:
    penalty = 2 + (index * 5) % 23
    deadline = 5 + (index * 7) % 31
    if lang == "ru":
        return f"Договор №{identifier} устанавливает штраф {penalty}% и срок {deadline} дней."
    return f"Contract {identifier} sets a penalty of {penalty}% and a deadline of {deadline} days."


def question(lang: str, domain: str, entity: str, index: int, query_class: str) -> str:
    _, primary, secondary, u1, _ = TERMS[lang][domain]
    a, _, _, _, reference = facts(index)
    surface = LANG[lang]
    if query_class == "FACTUAL":
        return surface["what"].format(entity=entity, label=primary)
    if query_class == "PARAPHRASE":
        return surface["paraphrase"].format(entity=entity)
    if query_class == "LEXICAL_EXACT":
        return f"{entity} {primary} {a}{u1}"
    if query_class == "REFERENCE":
        return surface["reference"].format(
            refword=surface["refword"], ref=reference, entity=entity, label=primary
        )
    if query_class == "NUMERIC":
        return surface["what"].format(entity=entity, label=secondary)
    if query_class == "TEMPORAL":
        return surface["date"].format(entity=entity, dateword=surface["dateword"])
    if query_class == "MULTI_INTENT":
        return surface["multi"].format(entity=entity, primary=primary, secondary=secondary)
    if query_class == "COMPARISON":
        return surface["compare"].format(entity=entity)
    if query_class == "CROSS_LANGUAGE":
        return surface["what"].format(entity=entity, label=primary)
    raise ValueError(f"unsupported semantic query class {query_class}")


def build() -> tuple[list[dict], list[dict]]:
    documents: list[dict] = []
    queries: list[dict] = []

    for index in range(ANSWERABLE_COUNT):
        query_class = ANSWERABLE_CLASSES[index % len(ANSWERABLE_CLASSES)]
        domain = DOMAINS[index % len(DOMAINS)]
        lang = LANGUAGES[index % len(LANGUAGES)]
        if query_class in {"IDENTIFIER_ONLY", "IDENTIFIER_SEMANTIC"}:
            domain = "LEGAL"
            lang = "en" if index % 2 == 0 else "ru"

        entity = f"AkmEntity{index + 1:03d}"
        document_id = f"bench-doc-{index + 1:03d}"
        title = f"AkmAI {domain.title()} Fixture {index + 1:03d}"
        doc_lang = lang

        if query_class in {"IDENTIFIER_ONLY", "IDENTIFIER_SEMANTIC"}:
            identifier = f"KZ-2026-{100000 + index:06d}"
            text = identifier_document(lang, identifier, index)
            if query_class == "IDENTIFIER_ONLY":
                qtext = f"договор {identifier}" if lang == "ru" else f"contract {identifier}"
            else:
                qtext = (
                    f"Какой штраф установлен по договору {identifier}?"
                    if lang == "ru"
                    else f"What penalty applies under contract {identifier}?"
                )
        else:
            if query_class == "CROSS_LANGUAGE":
                doc_lang = "ru" if lang == "en" else "en"
            text = document_text(doc_lang, domain, entity, index, query_class)
            qtext = question(lang, domain, entity, index, query_class)

        chunk_id = child_chunk_id(document_id, title, text)
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
                "difficulty": DIFFICULTIES[index % len(DIFFICULTIES)],
                "question": qtext,
                "answerable": True,
                "relevantDocumentIds": [document_id],
                "relevantChunkIds": [chunk_id],
                "forbiddenChunkIds": [],
            }
        )

    for offset in range(UNANSWERABLE_COUNT):
        index = ANSWERABLE_COUNT + offset
        lang = LANGUAGES[offset % len(LANGUAGES)]
        domain = DOMAINS[offset % len(DOMAINS)]
        _, primary, _, _, _ = TERMS[lang][domain]
        entity = f"MissingEntity{offset + 1:03d}"
        qtext = LANG[lang]["missing"].format(entity=entity, label=primary)
        queries.append(
            {
                "id": f"q-{index + 1:03d}",
                "language": lang,
                "domain": domain,
                "queryClass": "UNANSWERABLE",
                "difficulty": DIFFICULTIES[index % len(DIFFICULTIES)],
                "question": qtext,
                "answerable": False,
                "relevantDocumentIds": [],
                "relevantChunkIds": [],
                "forbiddenChunkIds": [],
            }
        )

    validate(documents, queries)
    return documents, queries


def validate(documents: list[dict], queries: list[dict]) -> None:
    assert len(documents) == ANSWERABLE_COUNT
    assert len(queries) == ANSWERABLE_COUNT + UNANSWERABLE_COUNT
    assert len({d["id"] for d in documents}) == len(documents)
    assert len({q["id"] for q in queries}) == len(queries)

    unanswerable = sum(not q["answerable"] for q in queries)
    ratio = unanswerable / len(queries)
    assert 0.20 <= ratio <= 0.30

    language_counts = Counter(q["language"] for q in queries)
    domain_counts = Counter(q["domain"] for q in queries)
    class_counts = Counter(q["queryClass"] for q in queries)
    difficulty_counts = Counter(q["difficulty"] for q in queries)
    assert all(language_counts[lang] >= 10 for lang in LANGUAGES)
    assert all(domain_counts[domain] >= 40 for domain in DOMAINS)
    assert all(class_counts[name] >= 10 for name in ANSWERABLE_CLASSES + ["UNANSWERABLE"])
    assert all(difficulty_counts[name] > 0 for name in DIFFICULTIES)

    document_ids = {d["id"] for d in documents}
    for q in queries:
        if q["answerable"]:
            assert q["queryClass"] != "UNANSWERABLE"
            assert q["relevantDocumentIds"] and q["relevantChunkIds"]
            assert set(q["relevantDocumentIds"]).issubset(document_ids)
        else:
            assert q["queryClass"] == "UNANSWERABLE"
            assert not q["relevantDocumentIds"] and not q["relevantChunkIds"]


def write_jsonl(path: Path, rows: list[dict]) -> None:
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")))
            handle.write("\n")


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
        "benchmarkVersion": BENCHMARK_VERSION,
        "corpusVersion": CORPUS_VERSION,
        "releaseQualified": True,
        "metadata": {
            "owner": "akmai-quality",
            "annotationPolicy": "deterministic-controlled-fixture-v1",
            "corpusKind": "CONTROLLED_SYNTHETIC",
            "realWorldQualityClaim": "false",
            "generator": "generate_controlled_corpus.py",
            "queryCount": str(len(queries)),
            "documentCount": str(len(documents)),
        },
    }
    (root / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    write_jsonl(root / "documents.jsonl", documents)
    write_jsonl(root / "queries.jsonl", queries)

    print(
        json.dumps(
            {
                "root": str(root),
                "documents": len(documents),
                "queries": len(queries),
                "unanswerable": UNANSWERABLE_COUNT,
                "corpusVersion": CORPUS_VERSION,
            },
            sort_keys=True,
        )
    )


if __name__ == "__main__":
    main()
