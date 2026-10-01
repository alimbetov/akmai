package kz.alimbetov.akmai.knowledge.chunking;

import java.util.Locale;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import kz.alimbetov.akmai.knowledge.model.SemanticUnitType;
import org.springframework.stereotype.Component;

@Component
public class DomainSemanticClassifier {

    public SemanticUnitType classify(String text, KnowledgeDomain domain) {
        String value = text.toLowerCase(Locale.ROOT);

        if (containsAny(value, "за исключением", "except", "unless", "қоспағанда", "除外")) {
            return SemanticUnitType.EXCEPTION;
        }
        if (containsAny(value, "обязан", "must", "shall", "міндетті", "应当")) {
            return SemanticUnitType.OBLIGATION;
        }
        if (containsAny(value, "запрещ", "must not", "prohibited", "тыйым", "禁止")) {
            return SemanticUnitType.PROHIBITION;
        }
        if (containsAny(value, "вправе", "may", "құқылы", "可以")) {
            return SemanticUnitType.RIGHT;
        }
        if (containsAny(value, "не позднее", "within", "days", "күн", "日内")) {
            return SemanticUnitType.DEADLINE;
        }

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
            if (containsAny(value, "показан", "indication", "көрсетілім", "适应症")) {
                return SemanticUnitType.INDICATION;
            }
        }

        return SemanticUnitType.PARAGRAPH;
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
