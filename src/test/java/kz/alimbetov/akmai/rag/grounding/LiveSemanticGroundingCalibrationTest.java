package kz.alimbetov.akmai.rag.grounding;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(properties = {
        "akmai.security.enabled=false",
        "akmai.reconciliation.enabled=false",
        "akmai.reembedding.auto-migrate=false"
})
@EnabledIfEnvironmentVariable(
        named = "AKMAI_SEMANTIC_GROUNDING_CALIBRATION",
        matches = "true"
)
class LiveSemanticGroundingCalibrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(
                    DockerImageName.parse("pgvector/pgvector:pg17")
                            .asCompatibleSubstituteFor("postgres")
            )
                    .withDatabaseName("akmai")
                    .withUsername("akmai")
                    .withPassword("akmai");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add(
                "spring.ai.ollama.base-url",
                () -> required("AKMAI_LIVE_OLLAMA_BASE_URL")
        );
    }

    @Autowired
    SemanticEntailmentClient entailmentClient;
    @Autowired
    ObjectMapper objectMapper;

    @Test
    void calibratesSupportedContradictedAndInsufficientAcrossRuKkEn()
            throws Exception {
        List<CalibrationCase> cases = cases();
        List<SemanticEntailmentClient.ClaimEvidence> batch = cases.stream()
                .map(value -> new SemanticEntailmentClient.ClaimEvidence(
                        value.claim(),
                        value.evidence()
                ))
                .toList();

        List<SemanticEntailmentClient.EntailmentStatus> actual =
                entailmentClient.evaluate(batch);
        assertThat(actual).hasSize(cases.size());

        int correct = 0;
        int contradictedExpected = 0;
        int contradictedDetected = 0;
        int supportedExpected = 0;
        int supportedRejected = 0;
        List<Map<String, Object>> rows = new ArrayList<>();

        for (int index = 0; index < cases.size(); index++) {
            CalibrationCase testCase = cases.get(index);
            var predicted = actual.get(index);
            if (predicted == testCase.expected()) {
                correct++;
            }
            if (testCase.expected()
                    == SemanticEntailmentClient.EntailmentStatus.CONTRADICTED) {
                contradictedExpected++;
                if (predicted
                        == SemanticEntailmentClient.EntailmentStatus.CONTRADICTED) {
                    contradictedDetected++;
                }
            }
            if (testCase.expected()
                    == SemanticEntailmentClient.EntailmentStatus.SUPPORTED) {
                supportedExpected++;
                if (predicted
                        != SemanticEntailmentClient.EntailmentStatus.SUPPORTED) {
                    supportedRejected++;
                }
            }
            rows.add(Map.of(
                    "id", testCase.id(),
                    "language", testCase.language(),
                    "category", testCase.category(),
                    "expected", testCase.expected().name(),
                    "actual", predicted.name()
            ));
        }

        double accuracy = ratio(correct, cases.size());
        double contradictionRecall = ratio(
                contradictedDetected,
                contradictedExpected
        );
        double supportedFalseRejectRate = ratio(
                supportedRejected,
                supportedExpected
        );

        LinkedHashMap<String, Object> report = new LinkedHashMap<>();
        report.put("benchmarkVersion", "semantic-grounding-calibration-v1");
        report.put("generatedAt", Instant.now().toString());
        report.put("gitSha", env("GITHUB_SHA", "local"));
        report.put("caseCount", cases.size());
        report.put("languages", List.of("ru", "kk", "en"));
        report.put("accuracy", accuracy);
        report.put("contradictionRecall", contradictionRecall);
        report.put("supportedFalseRejectRate", supportedFalseRejectRate);
        report.put("cases", rows);

        Path output = Path.of(
                "target",
                "quality",
                "semantic-grounding-calibration.json"
        );
        Files.createDirectories(output.getParent());
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(output.toFile(), report);

        assertThat(accuracy).isGreaterThanOrEqualTo(0.80);
        assertThat(contradictionRecall).isGreaterThanOrEqualTo(0.85);
        assertThat(supportedFalseRejectRate).isLessThanOrEqualTo(0.15);
    }

    private double ratio(int numerator, int denominator) {
        return denominator <= 0 ? 0.0 : (double) numerator / denominator;
    }

    private List<CalibrationCase> cases() {
        return List.of(
                c("ru-supported-1", "ru", "direct", "Договор можно расторгнуть при просрочке более 30 дней.", "Банк вправе расторгнуть договор, если просрочка превышает 30 календарных дней.", "SUPPORTED"),
                c("ru-contradicted-negation", "ru", "negation", "Препарат разрешен при тяжелой почечной недостаточности.", "Препарат противопоказан при тяжелой почечной недостаточности.", "CONTRADICTED"),
                c("ru-insufficient-subject", "ru", "wrong-subject", "Поставщик обязан уведомить за 15 дней.", "Покупатель обязан уведомить банк за 15 дней.", "INSUFFICIENT"),
                c("ru-contradicted-time", "ru", "temporal", "Оплата должна быть произведена в течение 60 дней.", "Покупатель обязан оплатить счет в течение 30 календарных дней.", "CONTRADICTED"),
                c("ru-supported-exception", "ru", "exception", "Правило не применяется, если задержка возникла из-за ошибки банка.", "При просрочке более 30 дней банк вправе расторгнуть договор. Это правило не применяется, если просрочка возникла из-за ошибки банка.", "SUPPORTED"),
                c("ru-insufficient-partial", "ru", "partial", "Неустойка составляет 0,1% и договор автоматически прекращается.", "Неустойка составляет 0,1% за каждый день просрочки.", "INSUFFICIENT"),

                c("kk-supported-1", "kk", "direct", "Төлем 30 күн ішінде жасалуы тиіс.", "Сатып алушы шотты алғаннан кейін 30 күнтізбелік күн ішінде төлем жасауға міндетті.", "SUPPORTED"),
                c("kk-contradicted-negation", "kk", "negation", "Дәрі ауыр бүйрек жеткіліксіздігінде қолдануға болады.", "Дәрі ауыр бүйрек жеткіліксіздігінде қолдануға қарсы көрсетілген.", "CONTRADICTED"),
                c("kk-insufficient-subject", "kk", "wrong-subject", "Банк 10 күн бұрын хабарлауы тиіс.", "Клиент 10 күн бұрын жазбаша хабарлауы тиіс.", "INSUFFICIENT"),
                c("kk-contradicted-number", "kk", "numeric", "Айыппұл күніне 1% құрайды.", "Айыппұл әр кешіктірілген күн үшін 0,1% құрайды.", "CONTRADICTED"),
                c("kk-supported-exception", "kk", "exception", "Банк қатесінен болған кешігуге ереже қолданылмайды.", "30 күннен асқан кешігуде шарт бұзылуы мүмкін, бірақ банк қатесінен болған кешігуге бұл ереже қолданылмайды.", "SUPPORTED"),
                c("kk-insufficient-partial", "kk", "partial", "Құжат бес жыл сақталады және кейін міндетті түрде жойылады.", "Құжат шарт аяқталғаннан кейін бес жыл сақталуы тиіс.", "INSUFFICIENT"),

                c("en-supported-1", "en", "direct", "POST /api/payments returns 409 for a duplicate Idempotency-Key.", "POST /api/payments returns HTTP 409 when an operation with the same Idempotency-Key already exists.", "SUPPORTED"),
                c("en-contradicted-negation", "en", "negation", "The medicine is permitted in severe renal failure.", "The medicine is contraindicated in severe renal failure.", "CONTRADICTED"),
                c("en-insufficient-subject", "en", "wrong-subject", "The supplier must notify the customer 15 days in advance.", "The customer must notify the bank 15 days in advance.", "INSUFFICIENT"),
                c("en-contradicted-modal", "en", "modal", "The operator may retain the records for five years.", "The operator must retain the records for five years.", "CONTRADICTED"),
                c("en-supported-exception", "en", "exception", "The termination rule does not apply when the delay was caused by a bank error.", "The bank may terminate after more than 30 days of delay. The rule does not apply when the delay was caused by a bank error.", "SUPPORTED"),
                c("en-insufficient-partial", "en", "partial", "The service retries three times and then permanently disables the account.", "The service retries the request three times.", "INSUFFICIENT")
        );
    }

    private CalibrationCase c(
            String id,
            String language,
            String category,
            String claim,
            String evidence,
            String expected
    ) {
        return new CalibrationCase(
                id,
                language,
                category,
                claim,
                List.of(evidence),
                SemanticEntailmentClient.EntailmentStatus.valueOf(expected)
        );
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable " + name);
        }
        return value;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private record CalibrationCase(
            String id,
            String language,
            String category,
            String claim,
            List<String> evidence,
            SemanticEntailmentClient.EntailmentStatus expected
    ) {
    }
}
