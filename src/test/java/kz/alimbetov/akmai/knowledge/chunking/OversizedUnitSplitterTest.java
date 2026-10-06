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
        var unit = unit(text);

        var parts = splitter.split(unit, 180);

        assertThat(parts).hasSizeGreaterThan(1);
        assertThat(parts)
                .allSatisfy(part -> assertThat(estimator.estimate(part.text()))
                        .isLessThanOrEqualTo(180));
    }

    @Test
    void chineseTextRespectsConservativeHardMaximum() {
        String text = "这是一个用于测试分块边界的句子。".repeat(200);
        var parts = splitter.split(unit(text), 120, 140, "zh");

        assertThat(parts).hasSizeGreaterThan(1);
        assertThat(parts)
                .allSatisfy(part -> assertThat(estimator.estimate(part.text()))
                        .isLessThanOrEqualTo(140));
    }

    @Test
    void prefersSentenceBoundaryInsidePreferredWindow() {
        String sentence = "Alpha beta gamma delta epsilon zeta eta theta. ";
        String text = sentence.repeat(80);
        var parts = splitter.split(unit(text), 80, 100, "en");

        assertThat(parts).hasSizeGreaterThan(1);
        assertThat(parts.getFirst().text()).endsWith(".");
        assertThat(estimator.estimate(parts.getFirst().text()))
                .isBetween(80, 100);
    }

    private SemanticUnit unit(String text) {
        return new SemanticUnit(
                text,
                "section",
                SemanticUnitType.RULE,
                false
        );
    }
}
