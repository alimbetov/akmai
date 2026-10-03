package kz.alimbetov.akmai.rag.quality;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import kz.alimbetov.akmai.config.OllamaTransportConfiguration;
import kz.alimbetov.akmai.rag.retrieval.RetrievalProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.embedding.EmbeddingModel;

@EnabledIfEnvironmentVariable(
        named = "AKMAI_LIVE_QUALITY",
        matches = "true"
)
class LiveEmbeddingRetrievalQualityTest {

    private static final List<Case> CASES = List.of(
            new Case(
                    "kk-live",
                    "kk",
                    "Қандай доза қажет?",
                    "Ұсынылатын доза 10 мг. Қандай доза қажет деген сұраққа осы бөлім жауап береді.",
                    "Компанияның заңды мекенжайы Алматы қаласында орналасқан."
            ),
            new Case(
                    "ru-live",
                    "ru",
                    "Какие противопоказания указаны?",
                    "Противопоказания: препарат нельзя применять при тяжелой почечной недостаточности.",
                    "Договор может быть расторгнут по соглашению сторон."
            ),
            new Case(
                    "en-live",
                    "en",
                    "What monitoring is required?",
                    "Monitoring is required weekly: measure blood pressure and renal function.",
                    "The invoice is payable within thirty calendar days."
            ),
            new Case(
                    "zh-live",
                    "zh",
                    "剂量是多少？",
                    "推荐剂量是每日一次10毫克。本节说明剂量是多少。",
                    "合同可由双方书面协议终止。"
            ),
            new Case(
                    "de-live",
                    "de",
                    "Welche Kontraindikationen sind angegeben?",
                    "Kontraindikationen: Das Arzneimittel darf bei schwerer Niereninsuffizienz nicht angewendet werden.",
                    "Der Vertrag kann schriftlich von beiden Parteien beendet werden."
            ),
            new Case(
                    "fr-live",
                    "fr",
                    "Quelle surveillance est requise?",
                    "Une surveillance hebdomadaire de la pression artérielle et de la fonction rénale est requise.",
                    "La facture doit être payée dans un délai de trente jours."
            ),
            new Case(
                    "es-live",
                    "es",
                    "¿Qué dosis se recomienda?",
                    "La dosis recomendada es de 10 mg una vez al día.",
                    "El contrato podrá terminarse por acuerdo escrito."
            ),
            new Case(
                    "pt-live",
                    "pt",
                    "Qual monitorização é necessária?",
                    "É necessária monitorização semanal da pressão arterial e da função renal.",
                    "A fatura deve ser paga no prazo de trinta dias."
            ),
            new Case(
                    "it-live",
                    "it",
                    "Quale dose è richiesta?",
                    "La dose raccomandata è di 10 mg una volta al giorno.",
                    "Il contratto può essere risolto mediante accordo scritto."
            ),
            new Case(
                    "tr-live",
                    "tr",
                    "Hangi doz gereklidir?",
                    "Önerilen doz günde bir kez 10 mg'dır.",
                    "Sözleşme tarafların yazılı anlaşmasıyla sona erdirilebilir."
            ),
            new Case(
                    "el-live",
                    "el",
                    "Ποια δόση απαιτείται;",
                    "Η συνιστώμενη δόση είναι 10 mg μία φορά την ημέρα.",
                    "Η σύμβαση μπορεί να λυθεί με γραπτή συμφωνία."
            )
    );

    @Test
    void liveOllamaEmbeddingsMeetMultilingualNearestNeighborGate() {
        String baseUrl = required("AKMAI_LIVE_OLLAMA_BASE_URL");
        String modelName = required("AKMAI_LIVE_EMBEDDING_MODEL");

        RetrievalProperties properties = retrievalProperties();
        OllamaTransportConfiguration configuration =
                new OllamaTransportConfiguration();
        EmbeddingModel model = configuration.retrievalEmbeddingModel(
                configuration.retrievalOllamaApi(baseUrl, properties),
                modelName
        );

        List<RetrievalBenchmarkResult> results = new ArrayList<>();
        for (Case testCase : CASES) {
            List<float[]> embeddings = model.embed(List.of(
                    testCase.query(),
                    testCase.relevantText(),
                    testCase.noiseText()
            ));
            assertThat(embeddings).hasSize(3);

            float[] query = embeddings.get(0);
            List<Scored> ranked = List.of(
                    new Scored(
                            testCase.id() + "-relevant",
                            cosine(query, embeddings.get(1))
                    ),
                    new Scored(
                            testCase.id() + "-noise",
                            cosine(query, embeddings.get(2))
                    )
            ).stream()
                    .sorted(Comparator.comparingDouble(Scored::score).reversed())
                    .toList();

            RetrievalBenchmarkCase benchmark = new RetrievalBenchmarkCase(
                    testCase.id(),
                    testCase.language(),
                    testCase.query(),
                    Set.of(testCase.id() + "-relevant")
            );
            RetrievalBenchmarkResult result =
                    RetrievalBenchmarkEvaluator.evaluate(
                            benchmark,
                            ranked.stream().map(Scored::id).toList()
                    );
            results.add(result);

            System.out.printf(
                    "LIVE_QUALITY language=%s case=%s relevantScore=%.4f noiseScore=%.4f mrr=%.3f ndcg10=%.3f%n",
                    testCase.language(),
                    testCase.id(),
                    ranked.stream()
                            .filter(value -> value.id().endsWith("-relevant"))
                            .findFirst()
                            .orElseThrow()
                            .score(),
                    ranked.stream()
                            .filter(value -> value.id().endsWith("-noise"))
                            .findFirst()
                            .orElseThrow()
                            .score(),
                    result.reciprocalRank(),
                    result.ndcgAt10()
            );
        }

        assertThat(results)
                .allSatisfy(result -> {
                    assertThat(result.recallAt5()).isEqualTo(1.0);
                    assertThat(result.reciprocalRank()).isEqualTo(1.0);
                    assertThat(result.ndcgAt10()).isEqualTo(1.0);
                });
    }

    private RetrievalProperties retrievalProperties() {
        return new RetrievalProperties(
                4,
                32,
                10,
                0.0,
                10,
                10,
                10,
                60,
                2,
                1,
                3,
                4096,
                12,
                4,
                true,
                10,
                Duration.ofSeconds(2),
                0.15,
                Duration.ofSeconds(10),
                Duration.ofSeconds(5),
                Duration.ofSeconds(20),
                Duration.ofSeconds(10),
                3,
                1024
        );
    }

    private double cosine(float[] left, float[] right) {
        assertThat(left.length).isEqualTo(right.length);
        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        for (int index = 0; index < left.length; index++) {
            assertThat(Float.isFinite(left[index])).isTrue();
            assertThat(Float.isFinite(right[index])).isTrue();
            dot += (double) left[index] * right[index];
            leftNorm += (double) left[index] * left[index];
            rightNorm += (double) right[index] * right[index];
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Missing environment variable " + name
            );
        }
        return value;
    }

    private record Case(
            String id,
            String language,
            String query,
            String relevantText,
            String noiseText
    ) {
    }

    private record Scored(String id, double score) {
    }
}
