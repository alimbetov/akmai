package kz.alimbetov.akmai.rag.policy;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RagPolicyPromotionRejectTest {

    @Test
    void rejectingCanaryImmediatelyInvalidatesStagedPolicyCaches() {
        RagPolicyRegistryRepository repository = mock(RagPolicyRegistryRepository.class);
        ApprovedRetrievalPolicyProvider approved = mock(ApprovedRetrievalPolicyProvider.class);
        ShadowRetrievalPolicyProvider shadow = mock(ShadowRetrievalPolicyProvider.class);
        CanaryRetrievalPolicyProvider canary = mock(CanaryRetrievalPolicyProvider.class);
        String version = "retrieval-canary-v2";
        when(repository.find(RagPolicyType.RETRIEVAL, version))
                .thenReturn(Optional.of(policy(version, RagPolicyStatus.CANARY)));

        RagPolicyPromotionService service = new RagPolicyPromotionService(
                repository,
                approved,
                shadow,
                canary,
                null,
                null
        );

        service.reject(RagPolicyType.RETRIEVAL, version);

        verify(repository).reject(RagPolicyType.RETRIEVAL, version);
        verify(shadow).invalidate();
        verify(canary).invalidate();
        verify(approved, never()).invalidate();
    }

    @Test
    void approvedPolicyCannotBeRejectedThroughStagedLifecycle() {
        RagPolicyRegistryRepository repository = mock(RagPolicyRegistryRepository.class);
        ApprovedRetrievalPolicyProvider approved = mock(ApprovedRetrievalPolicyProvider.class);
        ShadowRetrievalPolicyProvider shadow = mock(ShadowRetrievalPolicyProvider.class);
        CanaryRetrievalPolicyProvider canary = mock(CanaryRetrievalPolicyProvider.class);
        String version = "retrieval-approved-v1";
        when(repository.find(RagPolicyType.RETRIEVAL, version))
                .thenReturn(Optional.of(policy(version, RagPolicyStatus.APPROVED)));

        RagPolicyPromotionService service = new RagPolicyPromotionService(
                repository,
                approved,
                shadow,
                canary,
                null,
                null
        );

        assertThatThrownBy(() -> service.reject(RagPolicyType.RETRIEVAL, version))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CANDIDATE, SHADOW or CANARY");

        verify(repository, never()).reject(RagPolicyType.RETRIEVAL, version);
        verify(shadow, never()).invalidate();
        verify(canary, never()).invalidate();
    }

    private RagPolicyRegistryRepository.PolicyRecord policy(
            String version,
            RagPolicyStatus status
    ) {
        return new RagPolicyRegistryRepository.PolicyRecord(
                RagPolicyType.RETRIEVAL,
                version,
                status,
                Map.of(),
                Map.of(),
                Map.of(),
                Instant.parse("2026-10-07T00:00:00Z"),
                null
        );
    }
}
