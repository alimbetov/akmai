package kz.alimbetov.akmai.knowledge.chunking;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;
import kz.alimbetov.akmai.knowledge.model.SemanticUnitType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class IndustryProfileSemanticRulesTest {

    private final List<IndustryProfile> profiles =
            new YamlIndustryProfileLoader().loadAll();

    @ParameterizedTest(name = "banking/{0}")
    @MethodSource("bankingCases")
    void bankingRulesWorkAcrossAllLanguages(
            String language,
            String text
    ) {
        assertType("banking", language, text, SemanticUnitType.RULE);
    }

    @ParameterizedTest(name = "pharmacology/{0}")
    @MethodSource("pharmacologyCases")
    void pharmacologyRulesWorkAcrossAllLanguages(
            String language,
            String text
    ) {
        assertType(
                "pharmacology",
                language,
                text,
                SemanticUnitType.DOSAGE
        );
    }

    @ParameterizedTest(name = "cybersecurity/{0}")
    @MethodSource("cybersecurityCases")
    void cybersecurityRulesWorkAcrossAllLanguages(
            String language,
            String text
    ) {
        assertType("cybersecurity", language, text, SemanticUnitType.RULE);
    }

    @ParameterizedTest(name = "public-administration/{0}")
    @MethodSource("publicAdministrationCases")
    void publicAdministrationRulesWorkAcrossAllLanguages(
            String language,
            String text
    ) {
        assertType(
                "public_administration",
                language,
                text,
                SemanticUnitType.DEADLINE
        );
    }

    private void assertType(
            String profileId,
            String language,
            String text,
            SemanticUnitType expected
    ) {
        IndustryProfile profile = profiles.stream()
                .filter(value -> value.code().id().equals(profileId))
                .findFirst()
                .orElseThrow();

        assertThat(profile.classifyType(
                text,
                LanguageProfiles.forCode(language)
        ))
                .as(profileId + "/" + language)
                .contains(expected);
    }

    static Stream<Arguments> bankingCases() {
        return Stream.of(
                Arguments.of("kk", "Капиталдың жеткіліктілігі нормативі есептеледі."),
                Arguments.of("ru", "Достаточность капитала рассчитывается ежедневно."),
                Arguments.of("en", "Capital adequacy is calculated daily."),
                Arguments.of("zh", "资本充足率应每日计算。"),
                Arguments.of("de", "Die Kapitaladäquanz wird täglich berechnet."),
                Arguments.of("fr", "L'adéquation des fonds propres est calculée quotidiennement."),
                Arguments.of("es", "La adecuación de capital se calcula diariamente."),
                Arguments.of("pt", "A adequação de capital é calculada diariamente."),
                Arguments.of("it", "L'adeguatezza patrimoniale è calcolata ogni giorno."),
                Arguments.of("tr", "Sermaye yeterliliği günlük hesaplanır."),
                Arguments.of("el", "Η κεφαλαιακή επάρκεια υπολογίζεται καθημερινά.")
        );
    }

    static Stream<Arguments> pharmacologyCases() {
        return Stream.of(
                Arguments.of("kk", "Ұсынылатын доза 10 мг."),
                Arguments.of("ru", "Рекомендуемая доза составляет 10 мг."),
                Arguments.of("en", "The recommended dose is 10 mg."),
                Arguments.of("zh", "推荐剂量为10毫克。"),
                Arguments.of("de", "Die empfohlene Dosis beträgt 10 mg."),
                Arguments.of("fr", "La dose recommandée est de 10 mg."),
                Arguments.of("es", "La dosis recomendada es de 10 mg."),
                Arguments.of("pt", "A dose recomendada é de 10 mg."),
                Arguments.of("it", "La dose raccomandata è di 10 mg."),
                Arguments.of("tr", "Önerilen doz 10 mg'dır."),
                Arguments.of("el", "Η συνιστώμενη δόση είναι 10 mg.")
        );
    }

    static Stream<Arguments> cybersecurityCases() {
        return Stream.of(
                Arguments.of("kk", "Көп факторлы аутентификация барлық әкімшілерге қолданылады."),
                Arguments.of("ru", "Многофакторная аутентификация обязательна для администраторов."),
                Arguments.of("en", "Multi-factor authentication is required for administrators."),
                Arguments.of("zh", "管理员必须使用多因素认证。"),
                Arguments.of("de", "Mehrfaktor-Authentifizierung ist für Administratoren erforderlich."),
                Arguments.of("fr", "L'authentification multifacteur est requise pour les administrateurs."),
                Arguments.of("es", "La autenticación multifactor es obligatoria para administradores."),
                Arguments.of("pt", "A autenticação multifator é obrigatória para administradores."),
                Arguments.of("it", "L'autenticazione multifattore è obbligatoria per gli amministratori."),
                Arguments.of("tr", "Çok faktörlü kimlik doğrulama yöneticiler için zorunludur."),
                Arguments.of("el", "Ο πολυπαραγοντικός έλεγχος ταυτότητας απαιτείται για διαχειριστές.")
        );
    }

    static Stream<Arguments> publicAdministrationCases() {
        return Stream.of(
                Arguments.of("kk", "Әкімшілік рәсім мерзімі 15 күн."),
                Arguments.of("ru", "Срок административной процедуры составляет 15 дней."),
                Arguments.of("en", "The administrative procedure deadline is 15 days."),
                Arguments.of("zh", "行政程序期限为15天。"),
                Arguments.of("de", "Die Frist des Verwaltungsverfahrens beträgt 15 Tage."),
                Arguments.of("fr", "Le délai de la procédure administrative est de 15 jours."),
                Arguments.of("es", "El plazo del procedimiento administrativo es de 15 días."),
                Arguments.of("pt", "O prazo do procedimento administrativo é de 15 dias."),
                Arguments.of("it", "Il termine del procedimento amministrativo è di 15 giorni."),
                Arguments.of("tr", "İdari usul süresi 15 gündür."),
                Arguments.of("el", "Η προθεσμία διοικητικής διαδικασίας είναι 15 ημέρες.")
        );
    }
}
