package kz.alimbetov.akmai.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EvidenceQualityAssessorTest {

    @Mock
    RetrievalObserver observer;

    @Test
    void exactAuthorityEvidenceIsStrong() {
        EvidenceQualityAssessor subject = subject();

        assertThat(subject.assess(List.of(hit(
                "doc-1",
                "c1",
                0,
                List.of()
        )))).isEqualTo(EvidenceQualityAssessor.EvidenceQuality.STRONG);
    }

    @Test
    void independentChannelsAcrossDocumentsAreStrong() {
        EvidenceQualityAssessor subject = subject();

        assertThat(subject.assess(List.of(
                hit(
                        "doc-1",
                        "c1",
                        2,
                        List.of(new RetrievalEvidence(
                                RetrievalType.VECTOR,
                                1,
                                0.9
                        ))
                ),
                hit(
                        "doc-2",
                        "c2",
                        2,
                        List.of(new RetrievalEvidence(
                                RetrievalType.LEXICAL,
                                1,
                                0.8
                        ))
                )
        ))).isEqualTo(EvidenceQualityAssessor.EvidenceQuality.STRONG);
    }

    @Test
    void singleNonAuthoritativeLaneIsWeakAndObserved() {
        EvidenceQualityAssessor subject = subject();
        List<RetrievalHit> hits = List.of(hit(
                "doc-1",
                "c1",
                2,
                List.of(new RetrievalEvidence(
                        RetrievalType.VECTOR,
                        1,
                        0.8
                ))
        ));

        assertThat(subject.observe(hits))
                .isEqualTo(EvidenceQualityAssessor.EvidenceQuality.WEAK);
        verify(observer).evidenceQuality("WEAK");
    }

    private EvidenceQualityAssessor subject() {
        return new EvidenceQualityAssessor(
                RetrievalIntelligenceProperties.defaults(),
                observer
        );
    }

    private RetrievalHit hit(
            String documentId,
            String chunkId,
            int authorityTier,
            List<RetrievalEvidence> evidence
    ) {
        return new RetrievalHit(
                RetrievalType.VECTOR,
                1L,
                documentId,
                1L,
                chunkId,
                "text",
                Map.of("authorityTier", authorityTier),
                evidence,
                1.0
        );
    }
}
