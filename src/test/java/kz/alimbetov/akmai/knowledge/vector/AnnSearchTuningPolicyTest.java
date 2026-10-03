package kz.alimbetov.akmai.knowledge.vector;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AnnSearchTuningPolicyTest {

    @Test
    void adaptsCandidateBreadthAndEfSearchToAclScope() {
        assertThat(AnnSearchTuningPolicy.forAccessScope(1))
                .isEqualTo(new AnnSearchTuningPolicy.AnnSearchTuning(1, 40));
        assertThat(AnnSearchTuningPolicy.forAccessScope(2))
                .isEqualTo(new AnnSearchTuningPolicy.AnnSearchTuning(1, 40));
        assertThat(AnnSearchTuningPolicy.forAccessScope(4))
                .isEqualTo(new AnnSearchTuningPolicy.AnnSearchTuning(4, 40));
        assertThat(AnnSearchTuningPolicy.forAccessScope(8))
                .isEqualTo(new AnnSearchTuningPolicy.AnnSearchTuning(2, 120));
        assertThat(AnnSearchTuningPolicy.forAccessScope(16))
                .isEqualTo(new AnnSearchTuningPolicy.AnnSearchTuning(2, 120));
    }
}
