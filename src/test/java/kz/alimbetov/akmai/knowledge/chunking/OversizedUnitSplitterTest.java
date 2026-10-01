package kz.alimbetov.akmai.knowledge.chunking;

import static org.assertj.core.api.Assertions.assertThat;

import kz.alimbetov.akmai.knowledge.model.SemanticUnit;
import kz.alimbetov.akmai.knowledge.model.SemanticUnitType;
import org.junit.jupiter.api.Test;

class OversizedUnitSplitterTest {

    private final TokenEstimator estimator = new TokenEstimator();
    private final OversizedUnitSplitter splitter = new OversizedUnitSplitter(estimator);

    @Test
    void neverReturnsPartAboveHardMaximum() {
        String text = "Очень длинное предложение. ".repeat(1000);
        var unit = new SemanticUnit(
                text,
                "section",
                SemanticUnitType.RULE,
                false
        );

        var parts = splitter.split(unit, 180);

        assertThat(parts).hasSizeGreaterThan(1);
        assertThat(parts)
                .allSatisfy(part -> assertThat(estimator.estimate(part.text()))
                        .isLessThanOrEqualTo(180));
    }
}
