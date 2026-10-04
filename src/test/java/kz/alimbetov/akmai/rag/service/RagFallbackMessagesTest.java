package kz.alimbetov.akmai.rag.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import kz.alimbetov.akmai.rag.query.QueryLanguageDetector;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class RagFallbackMessagesTest {

    private final RagFallbackMessages messages =
            new RagFallbackMessages(new QueryLanguageDetector());

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void localizesInsufficientInformationForEverySupportedLanguage(
            String language,
            String question,
            String expected
    ) {
        assertThat(messages.insufficientInformation(question))
                .as(language)
                .isEqualTo(expected);
    }

    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of(
                        "kk",
                        "Қандай доза қажет?",
                        "Білім базасында жеткілікті ақпарат жоқ."
                ),
                Arguments.of(
                        "ru",
                        "Какие противопоказания указаны?",
                        "В базе знаний недостаточно информации."
                ),
                Arguments.of(
                        "en",
                        "What monitoring is required?",
                        "There is insufficient information in the knowledge base."
                ),
                Arguments.of(
                        "zh",
                        "剂量是多少？",
                        "知识库中的信息不足。"
                ),
                Arguments.of(
                        "de",
                        "Welche Kontraindikationen sind angegeben?",
                        "Die Wissensdatenbank enthält nicht genügend Informationen."
                ),
                Arguments.of(
                        "fr",
                        "Quelle surveillance est requise?",
                        "La base de connaissances ne contient pas suffisamment d’informations."
                ),
                Arguments.of(
                        "es",
                        "¿Qué dosis se recomienda?",
                        "La base de conocimientos no contiene información suficiente."
                ),
                Arguments.of(
                        "pt",
                        "Qual monitorização é necessária?",
                        "A base de conhecimento não contém informação suficiente."
                ),
                Arguments.of(
                        "it",
                        "Quale dose è richiesta?",
                        "La base di conoscenza non contiene informazioni sufficienti."
                ),
                Arguments.of(
                        "tr",
                        "Hangi doz gereklidir?",
                        "Bilgi tabanında yeterli bilgi yok."
                ),
                Arguments.of(
                        "el",
                        "Ποια δόση απαιτείται;",
                        "Η βάση γνώσεων δεν περιέχει επαρκείς πληροφορίες."
                )
        );
    }
}
