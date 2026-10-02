package kz.alimbetov.akmai.knowledge.chunking;

import java.util.Locale;
import java.util.regex.Pattern;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import kz.alimbetov.akmai.knowledge.model.SemanticUnitType;
import org.springframework.stereotype.Component;

@Component
public class DomainSemanticClassifier {

    public SemanticUnitType classify(String text, KnowledgeDomain domain) {
        String value = text == null ? "" : text.toLowerCase(Locale.ROOT);

        if (domain == KnowledgeDomain.MEDICAL) {
            if (containsAny(value, "противопоказ", "contraindicat", "қарсы көрсет", "禁忌")) {
                return SemanticUnitType.CONTRAINDICATION;
            }
            if (containsAny(value, "доз", "dose", "dosage", "доза", "剂量")) {
                return SemanticUnitType.DOSAGE;
            }
            if (containsAny(value, "взаимодейств", "interaction", "өзара әрекет", "相互作用")) {
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
                    "监控"
            )) {
                return SemanticUnitType.MONITORING;
            }
            if (containsAny(value, "показан", "indication", "көрсетілім", "适应症")) {
                return SemanticUnitType.INDICATION;
            }
        }

        if (containsAny(value, "за исключением", "қоспағанда", "除外")
                || containsPhrase(value, "except")
                || containsPhrase(value, "unless")) {
            return SemanticUnitType.EXCEPTION;
        }

        // Negative deontic phrases must precede their positive prefixes.
        if (containsAny(value, "запрещ", "тыйым", "禁止")
                || containsPhrase(value, "must not")
                || containsPhrase(value, "shall not")
                || containsPhrase(value, "may not")
                || containsPhrase(value, "prohibited")) {
            return SemanticUnitType.PROHIBITION;
        }

        if (containsAny(value, "обязан", "міндетті", "应当")
                || containsPhrase(value, "must")
                || containsPhrase(value, "shall")) {
            return SemanticUnitType.OBLIGATION;
        }

        if (containsAny(value, "вправе", "құқылы", "可以")
                || containsPhrase(value, "may")) {
            return SemanticUnitType.RIGHT;
        }

        if (containsAny(value, "не позднее", "күн", "日内")
                || containsPhrase(value, "within")
                || containsPhrase(value, "days")) {
            return SemanticUnitType.DEADLINE;
        }

        return SemanticUnitType.PARAGRAPH;
    }

    private boolean containsPhrase(String text, String phrase) {
        String[] tokens = phrase.split("\\s+");
        String regex = "(?iu)(?<![\\p{L}\\p{N}])"
                + java.util.Arrays.stream(tokens)
                        .map(Pattern::quote)
                        .collect(java.util.stream.Collectors.joining("\\s+"))
                + "(?![\\p{L}\\p{N}])";
        return Pattern.compile(regex).matcher(text).find();
    }

    private boolean containsAny(String text, String... terms) {
        for (String term : terms) {
            if (text.contains(term)) {
                return true;
            }
        }
        return false;
    }
}
