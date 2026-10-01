package kz.alimbetov.akmai.knowledge.chunking;

import java.util.ArrayList;
import java.util.List;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import kz.alimbetov.akmai.knowledge.model.SemanticUnit;
import kz.alimbetov.akmai.knowledge.model.SemanticUnitType;
import org.springframework.stereotype.Component;

@Component
public class AtomicUnitProtector {

    public List<SemanticUnit> protect(List<SemanticUnit> units, KnowledgeDomain domain) {
        List<SemanticUnit> result = new ArrayList<>();

        for (int i = 0; i < units.size(); i++) {
            SemanticUnit current = units.get(i);

            if (i + 1 < units.size()) {
                SemanticUnit next = units.get(i + 1);

                if (mustStayTogether(current.type(), next.type(), domain)
                        && sameSection(current, next)) {
                    result.add(new SemanticUnit(
                            current.text() + "\n" + next.text(),
                            current.sectionPath(),
                            current.type(),
                            true
                    ));
                    i++;
                    continue;
                }
            }

            result.add(current);
        }

        return result;
    }

    private boolean mustStayTogether(
            SemanticUnitType current,
            SemanticUnitType next,
            KnowledgeDomain domain
    ) {
        if (next == SemanticUnitType.EXCEPTION) {
            return current == SemanticUnitType.RULE
                    || current == SemanticUnitType.OBLIGATION
                    || current == SemanticUnitType.PROHIBITION
                    || current == SemanticUnitType.RIGHT
                    || current == SemanticUnitType.PARAGRAPH;
        }

        if (domain == KnowledgeDomain.MEDICAL) {
            return (current == SemanticUnitType.INDICATION && next == SemanticUnitType.DOSAGE)
                    || (current == SemanticUnitType.DOSAGE && next == SemanticUnitType.CONTRAINDICATION);
        }

        return false;
    }

    private boolean sameSection(SemanticUnit left, SemanticUnit right) {
        return java.util.Objects.equals(left.sectionPath(), right.sectionPath());
    }
}
