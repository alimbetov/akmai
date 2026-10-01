package kz.alimbetov.akmai.knowledge.chunking;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDocument;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import org.junit.jupiter.api.Test;

class SemanticChunkingDepthTest {

    private final SemanticChunker chunker = new SemanticChunker(
            new TextNormalizer(),
            new StructuralUnitExtractor(),
            new DomainSemanticClassifier(),
            new AtomicUnitProtector(),
            new CrossReferenceExtractor(),
            new EmbeddingTextBuilder(),
            new TokenEstimator(),
            new ChunkingProperties(750, 1200, 1800, 1),
            new OversizedUnitSplitter(new TokenEstimator()),
            new ChunkIdentity()
    );

    @Test
    void preservesFullRussianLegalHierarchy() {
        var chunks = chunker.chunk(document(
                "ru-law",
                "ru",
                KnowledgeDomain.LEGAL,
                """
                ЗАКОН О ДОГОВОРАХ

                ЧАСТЬ I

                ГЛАВА 2

                РАЗДЕЛ 3

                СТАТЬЯ 25. Расторжение договора

                ПАРАГРАФ 1

                ПОДПАРАГРАФ 1.1

                Банк вправе расторгнуть договор при существенном нарушении.
                """
        ));

        assertThat(chunks.getLast().sectionPath())
                .contains("ЗАКОН О ДОГОВОРАХ")
                .contains("ЧАСТЬ I")
                .contains("ГЛАВА 2")
                .contains("РАЗДЕЛ 3")
                .contains("СТАТЬЯ 25")
                .contains("ПАРАГРАФ 1")
                .contains("ПОДПАРАГРАФ 1.1");
    }

    @Test
    void supportsKazakhEnglishAndChineseLegalHierarchyFixtures() {
        assertHierarchy("kk-law", "kk", """
                ЗАҢ ТУРАЛЫ

                БӨЛІМ I

                ТАРАУ 2

                БӨЛІК 3

                БАП 25. Шартты бұзу

                ТАРМАҚ 1

                ТАРМАҚША 1.1

                Банк шартты бұзуға құқылы.
                """, "БАП 25");

        assertHierarchy("en-law", "en", """
                LAW ON CONTRACTS

                PART I

                CHAPTER 2

                SECTION 3

                ARTICLE 25. Termination

                PARAGRAPH 1

                SUBPARAGRAPH 1.1

                The bank may terminate the agreement.
                """, "ARTICLE 25");

        assertHierarchy("zh-law", "zh", """
                法律 合同法

                编 I

                章 2

                节 3

                条 25. 合同终止

                款 1

                项 1.1

                银行可以依法终止合同。
                """, "条 25");
    }

    @Test
    void emitsMedicalFactsAsSeparateAtomicChunksAcrossLanguages() {
        assertMedicalAtomic("med-ru", "ru", """
                Клиническая инструкция

                Показания: препарат показан для лечения артериальной гипертензии.
                Дозировка: начальная доза 10 мг один раз в сутки.
                Противопоказания: противопоказан при тяжёлой почечной недостаточности.
                """);

        assertMedicalAtomic("med-kk", "kk", """
                Клиникалық нұсқаулық

                Көрсетілім: препарат артериялық гипертензияны емдеуге көрсетілім береді.
                Доза: бастапқы доза тәулігіне бір рет 10 мг.
                Қарсы көрсетілім: ауыр бүйрек жеткіліксіздігінде қарсы көрсетілім бар.
                """);

        assertMedicalAtomic("med-en", "en", """
                Clinical guidance

                Indication: indicated for treatment of arterial hypertension.
                Dosage: initial dose is 10 mg once daily.
                Contraindication: contraindicated in severe renal failure.
                """);

        assertMedicalAtomic("med-zh", "zh", """
                临床指南

                适应症: 适应症为治疗高血压。
                剂量: 初始剂量为每日一次10毫克。
                禁忌: 严重肾功能衰竭属于禁忌。
                """);
    }


    @Test
    void protectsAllFiveMedicalAtomicFactTypesAcrossLanguages() {
        assertFiveMedicalAtomicFacts(
                "med-five-ru",
                "ru",
                """
                Клиническая инструкция

                Показания: препарат показан для лечения гипертензии.
                Дозировка: принимать 10 мг один раз в сутки.
                Противопоказания: противопоказан при тяжёлой почечной недостаточности.
                Взаимодействие: взаимодействие с варфарином усиливает риск кровотечения.
                Мониторинг: контролировать артериальное давление еженедельно.
                """,
                List.of(
                        "Показания:",
                        "Дозировка:",
                        "Противопоказания:",
                        "Взаимодействие:",
                        "Мониторинг:"
                )
        );

        assertFiveMedicalAtomicFacts(
                "med-five-kk",
                "kk",
                """
                Клиникалық нұсқаулық

                Көрсетілім: препарат гипертензияны емдеуге көрсетілім береді.
                Доза: тәулігіне бір рет 10 мг.
                Қарсы көрсетілім: ауыр бүйрек жеткіліксіздігінде қарсы көрсетілім бар.
                Өзара әрекет: варфаринмен өзара әрекет қан кету қаупін арттырады.
                Бақылау: артериялық қысымды апта сайын бақылау қажет.
                """,
                List.of(
                        "Көрсетілім:",
                        "Доза:",
                        "Қарсы көрсетілім:",
                        "Өзара әрекет:",
                        "Бақылау:"
                )
        );

        assertFiveMedicalAtomicFacts(
                "med-five-en",
                "en",
                """
                Clinical guidance

                Indication: indicated for treatment of hypertension.
                Dosage: take 10 mg once daily.
                Contraindication: contraindicated in severe renal failure.
                Interaction: interaction with warfarin increases bleeding risk.
                Monitoring: monitor blood pressure weekly.
                """,
                List.of(
                        "Indication:",
                        "Dosage:",
                        "Contraindication:",
                        "Interaction:",
                        "Monitoring:"
                )
        );

        assertFiveMedicalAtomicFacts(
                "med-five-zh",
                "zh",
                """
                临床指南

                适应症: 适应症为治疗高血压。
                剂量: 每日一次10毫克。
                禁忌: 严重肾功能衰竭属于禁忌。
                相互作用: 与华法林相互作用会增加出血风险。
                监测: 每周监测血压。
                """,
                List.of(
                        "适应症:",
                        "剂量:",
                        "禁忌:",
                        "相互作用:",
                        "监测:"
                )
        );
    }

    private void assertHierarchy(String id, String language, String text, String expectedLeaf) {
        var chunks = chunker.chunk(document(id, language, KnowledgeDomain.LEGAL, text));
        assertThat(chunks.getLast().sectionPath()).contains(expectedLeaf).contains(" > ");
    }


    private void assertFiveMedicalAtomicFacts(
            String id,
            String language,
            String text,
            java.util.List<String> markers
    ) {
        var chunks = chunker.chunk(document(id, language, KnowledgeDomain.MEDICAL, text));
        assertThat(chunks).hasSizeGreaterThanOrEqualTo(5);

        for (String marker : markers) {
            assertThat(chunks)
                    .filteredOn(chunk -> chunk.rawText().contains(marker))
                    .hasSize(1);
        }

        assertThat(chunks)
                .filteredOn(chunk -> markers.stream()
                        .anyMatch(marker -> chunk.rawText().contains(marker)))
                .allSatisfy(chunk -> assertThat(markers.stream()
                        .filter(marker -> chunk.rawText().contains(marker))
                        .count()).isEqualTo(1));
    }

    private void assertMedicalAtomic(String id, String language, String text) {
        var chunks = chunker.chunk(document(id, language, KnowledgeDomain.MEDICAL, text));
        assertThat(chunks).hasSizeGreaterThanOrEqualTo(3);
        assertThat(chunks.stream().map(chunk -> chunk.rawText().toLowerCase()).toList())
                .anyMatch(value -> value.contains("10 мг")
                        || value.contains("10 mg")
                        || value.contains("10毫克"));
        assertThat(chunks.stream().map(chunk -> chunk.rawText()).toList())
                .allMatch(value -> value.lines().filter(line -> !line.isBlank()).count() <= 2);
    }

    private KnowledgeDocument document(
            String id,
            String language,
            KnowledgeDomain domain,
            String text
    ) {
        return new KnowledgeDocument(
                id,
                id,
                text,
                language,
                domain,
                Map.of("source", id + ".md")
        );
    }
}
