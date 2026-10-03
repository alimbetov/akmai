package kz.alimbetov.akmai.knowledge.chunking;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import kz.alimbetov.akmai.knowledge.model.SemanticUnitType;
import org.springframework.stereotype.Component;

@Component
public class DomainSemanticClassifier {

    public SemanticUnitType classify(
            String text,
            KnowledgeDomain domain
    ) {
        String value = Normalizer.normalize(
                text == null ? "" : text,
                Normalizer.Form.NFC
        ).toLowerCase(Locale.ROOT);

        if (domain == KnowledgeDomain.MEDICAL) {
            if (containsAny(
                    value,
                    "противопоказ",
                    "contraindicat",
                    "қарсы көрсет",
                    "禁忌",
                    "kontraindikation",
                    "contre-indication",
                    "contre indication",
                    "contraindicación",
                    "contraindicação",
                    "controindicazione",
                    "kontrendikasyon",
                    "αντένδειξ"
            )) {
                return SemanticUnitType.CONTRAINDICATION;
            }
            if (containsAny(
                    value,
                    "доз",
                    "dose",
                    "dosage",
                    "剂量",
                    "dosis",
                    "posolog",
                    "doz",
                    "δόσ"
            )) {
                return SemanticUnitType.DOSAGE;
            }
            if (containsAny(
                    value,
                    "взаимодейств",
                    "interaction",
                    "өзара әрекет",
                    "相互作用",
                    "wechselwirkung",
                    "interacción",
                    "interação",
                    "interazione",
                    "etkileşim",
                    "αλληλεπίδρασ"
            )) {
                return SemanticUnitType.INTERACTION;
            }
            if (containsAny(
                    value,
                    "мониторинг",
                    "контролировать",
                    "monitoring",
                    "monitor ",
                    "бақылау",
                    "бақылаңыз",
                    "监测",
                    "监控",
                    "überwachung",
                    "surveillance",
                    "seguimiento",
                    "monitorización",
                    "monitorização",
                    "monitoramento",
                    "monitoraggio",
                    "izlem",
                    "monitorizasyon",
                    "παρακολούθησ"
            )) {
                return SemanticUnitType.MONITORING;
            }
            if (containsAny(
                    value,
                    "показан",
                    "indication",
                    "көрсетілім",
                    "适应症",
                    "indikation",
                    "indicación",
                    "indicação",
                    "indicazione",
                    "endikasyon",
                    "ένδειξ"
            )) {
                return SemanticUnitType.INDICATION;
            }
        }

        if (containsAny(
                value,
                "за исключением",
                "қоспағанда",
                "除外",
                "ausgenommen",
                "außer",
                "à l'exception",
                "sauf",
                "excepto",
                "salvo",
                "exceto",
                "excepto",
                "eccetto",
                "hariç",
                "εκτός",
                "εξαιρου"
        ) || containsPhrase(value, "except")
                || containsPhrase(value, "unless")) {
            return SemanticUnitType.EXCEPTION;
        }

        if (containsAny(
                value,
                "запрещ",
                "тыйым",
                "禁止",
                "verboten",
                "darf nicht",
                "ist untersagt",
                "interdit",
                "ne doit pas",
                "ne peut pas",
                "prohibido",
                "no debe",
                "no puede",
                "não deve",
                "não pode",
                "proibido",
                "vietato",
                "non deve",
                "non può",
                "yasaktır",
                "izin verilmez",
                "απαγορεύεται",
                "δεν πρέπει",
                "δεν επιτρέπεται"
        ) || containsPhrase(value, "must not")
                || containsPhrase(value, "shall not")
                || containsPhrase(value, "may not")
                || containsPhrase(value, "prohibited")) {
            return SemanticUnitType.PROHIBITION;
        }

        if (containsAny(
                value,
                "обязан",
                "міндетті",
                "应当",
                "ist verpflichtet",
                "muss ",
                "doit ",
                "est tenu",
                "debe ",
                "deberá",
                "deve ",
                "é obrigado",
                "è tenuto",
                "zorundadır",
                "gerekir",
                "πρέπει",
                "υποχρεούται"
        ) || containsPhrase(value, "must")
                || containsPhrase(value, "shall")) {
            return SemanticUnitType.OBLIGATION;
        }

        if (containsAny(
                value,
                "вправе",
                "құқылы",
                "可以",
                "ist berechtigt",
                "darf ",
                "a le droit",
                "peut ",
                "tiene derecho",
                "puede ",
                "podrá",
                "tem direito",
                "pode ",
                "ha diritto",
                "può",
                "hakkına sahiptir",
                "yapabilir",
                "δικαιούται",
                "μπορεί"
        ) || containsPhrase(value, "may")) {
            return SemanticUnitType.RIGHT;
        }

        if (containsAny(
                value,
                "не позднее",
                "күн",
                "日内",
                "spätestens",
                "innerhalb",
                "tage",
                "au plus tard",
                "dans un délai",
                "jours",
                "a más tardar",
                "dentro de",
                "días",
                "no prazo",
                "dias",
                "non oltre",
                "giorni",
                "en geç",
                "gün içinde",
                "εντός",
                "το αργότερο",
                "ημέρες"
        ) || containsPhrase(value, "within")
                || containsPhrase(value, "days")) {
            return SemanticUnitType.DEADLINE;
        }

        return SemanticUnitType.PARAGRAPH;
    }

    private boolean containsPhrase(
            String text,
            String phrase
    ) {
        String[] tokens = phrase.split("\\s+");
        String regex = "(?iu)(?<![\\p{L}\\p{N}])"
                + java.util.Arrays.stream(tokens)
                        .map(Pattern::quote)
                        .collect(
                                java.util.stream.Collectors.joining(
                                        "\\s+"
                                )
                        )
                + "(?![\\p{L}\\p{N}])";
        return Pattern.compile(regex).matcher(text).find();
    }

    private boolean containsAny(
            String text,
            String... terms
    ) {
        for (String term : terms) {
            if (text.contains(term)) {
                return true;
            }
        }
        return false;
    }
}
