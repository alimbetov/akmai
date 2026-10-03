package kz.alimbetov.akmai.knowledge.chunking;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDocument;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import kz.alimbetov.akmai.knowledge.model.SemanticUnitType;
import kz.alimbetov.akmai.knowledge.model.StructuralRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class MultilingualChunkingRulesTest {

    private final StructuralUnitExtractor extractor =
            new StructuralUnitExtractor();
    private final DomainSemanticClassifier classifier =
            new DomainSemanticClassifier();

    @ParameterizedTest(name = "{0}")
    @MethodSource("legalHeadings")
    void recognizesMultilingualLegalHeadings(
            String language,
            String heading
    ) {
        KnowledgeDocument document = new KnowledgeDocument(
                "law-" + language,
                "Root",
                heading + "\n\nBody text.",
                language,
                KnowledgeDomain.LEGAL,
                Map.of()
        );

        var units = extractor.extract(document, document.rawText());

        assertThat(units.getFirst().type())
                .isEqualTo(SemanticUnitType.HEADING);
        assertThat(units.getFirst().structuralRole())
                .isEqualTo(StructuralRole.HEADING);
        assertThat(units.getFirst().sectionPath())
                .contains(heading);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("medicalDosage")
    void classifiesDosageAcrossSupportedLanguages(
            String language,
            String text
    ) {
        assertThat(classifier.classify(
                text,
                KnowledgeDomain.MEDICAL
        )).as(language).isEqualTo(SemanticUnitType.DOSAGE);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("legalProhibitions")
    void classifiesLegalProhibitionAcrossSupportedLanguages(
            String language,
            String text
    ) {
        assertThat(classifier.classify(
                text,
                KnowledgeDomain.LEGAL
        )).as(language).isEqualTo(SemanticUnitType.PROHIBITION);
    }

    @Test
    void sentenceBoundaryProfilesProtectAbbreviationsAndIdentifiers() {
        assertThat(MultilingualSentenceSplitter.split(
                "Dr. Müller prüft Art. 5. Danach folgt die Kontrolle.",
                "de"
        )).containsExactly(
                "Dr. Müller prüft Art. 5.",
                "Danach folgt die Kontrolle."
        );

        assertThat(MultilingualSentenceSplitter.split(
                "Mme. Dupont vérifie la dose. Le contrôle continue.",
                "fr"
        )).containsExactly(
                "Mme. Dupont vérifie la dose.",
                "Le contrôle continue."
        );

        assertThat(MultilingualSentenceSplitter.split(
                "API v1.2 bleibt stabil. Danach folgt Text.",
                "de"
        )).containsExactly(
                "API v1.2 bleibt stabil.",
                "Danach folgt Text."
        );

        assertThat(MultilingualSentenceSplitter.split(
                "Ποια δόση απαιτείται; Η παρακολούθηση είναι εβδομαδιαία.",
                "el"
        )).containsExactly(
                "Ποια δόση απαιτείται;",
                "Η παρακολούθηση είναι εβδομαδιαία."
        );
    }

    static Stream<Arguments> legalHeadings() {
        return Stream.of(
                Arguments.of("kk", "БАП 5. Міндеттер"),
                Arguments.of("ru", "СТАТЬЯ 5. Обязанности"),
                Arguments.of("en", "ARTICLE 5. Duties"),
                Arguments.of("zh", "第五条 义务"),
                Arguments.of("de", "ARTIKEL 5. Pflichten"),
                Arguments.of("fr", "ARTICLE 5. Obligations"),
                Arguments.of("es", "ARTÍCULO 5. Obligaciones"),
                Arguments.of("pt", "ARTIGO 5. Obrigações"),
                Arguments.of("it", "ARTICOLO 5. Obblighi"),
                Arguments.of("tr", "MADDE 5. Yükümlülükler"),
                Arguments.of("el", "ΆΡΘΡΟ 5. Υποχρεώσεις")
        );
    }

    static Stream<Arguments> medicalDosage() {
        return Stream.of(
                Arguments.of("kk", "Доза тәулігіне 10 мг."),
                Arguments.of("ru", "Доза составляет 10 мг."),
                Arguments.of("en", "The dosage is 10 mg daily."),
                Arguments.of("zh", "剂量为每日10毫克。"),
                Arguments.of("de", "Die Dosis beträgt täglich 10 mg."),
                Arguments.of("fr", "La dose est de 10 mg par jour."),
                Arguments.of("es", "La dosis es de 10 mg al día."),
                Arguments.of("pt", "A dose é de 10 mg por dia."),
                Arguments.of("it", "La dose è di 10 mg al giorno."),
                Arguments.of("tr", "Doz günde 10 mg'dır."),
                Arguments.of("el", "Η δόση είναι 10 mg ημερησίως.")
        );
    }

    static Stream<Arguments> legalProhibitions() {
        return Stream.of(
                Arguments.of("kk", "Бұл әрекетке тыйым салынады."),
                Arguments.of("ru", "Передача данных запрещена."),
                Arguments.of("en", "The bank must not disclose the data."),
                Arguments.of("zh", "禁止披露这些数据。"),
                Arguments.of("de", "Die Weitergabe der Daten ist verboten."),
                Arguments.of("fr", "La divulgation des données est interdite."),
                Arguments.of("es", "Está prohibido divulgar los datos."),
                Arguments.of("pt", "É proibido divulgar os dados."),
                Arguments.of("it", "È vietato divulgare i dati."),
                Arguments.of("tr", "Verilerin açıklanması yasaktır."),
                Arguments.of("el", "Απαγορεύεται η κοινοποίηση των δεδομένων.")
        );
    }
}
