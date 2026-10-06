package kz.alimbetov.akmai.rag.assurance;

import java.util.List;
import kz.alimbetov.akmai.knowledge.model.KnowledgeChunk;
import kz.alimbetov.akmai.rag.assurance.assertion.ChunkAssertions;
import kz.alimbetov.akmai.rag.assurance.assertion.CitationAssertions;
import kz.alimbetov.akmai.rag.assurance.assertion.ContextAssertions;
import kz.alimbetov.akmai.rag.assurance.assertion.GroundingAssertions;
import kz.alimbetov.akmai.rag.assurance.assertion.LifecycleAssertions;
import kz.alimbetov.akmai.rag.assurance.assertion.RetrievalAssertions;
import kz.alimbetov.akmai.rag.assurance.assertion.SecurityAssertions;
import kz.alimbetov.akmai.rag.retrieval.AnswerGroundingVerifier;
import kz.alimbetov.akmai.rag.retrieval.CitationValidator;
import kz.alimbetov.akmai.rag.retrieval.RetrievalHit;

/**
 * Entry point for reusable RAG contract assertions.
 *
 * <p>The facade intentionally contains no assertion logic. Named factories avoid
 * ambiguous generic {@code List<T>} overloads after type erasure and keep contract
 * intent visible at call sites.</p>
 */
public final class RagAssertions {

    private RagAssertions() {
    }

    public static ChunkAssertions chunks(List<KnowledgeChunk> chunks) {
        return new ChunkAssertions(chunks);
    }

    public static RetrievalAssertions retrieval(List<RetrievalHit> hits) {
        return new RetrievalAssertions(hits);
    }

    public static ContextAssertions context(List<RetrievalHit> context) {
        return new ContextAssertions(context);
    }

    public static CitationAssertions citation(
            CitationValidator.CitationValidation validation
    ) {
        return new CitationAssertions(validation);
    }

    public static GroundingAssertions grounding(
            AnswerGroundingVerifier.GroundingValidation validation
    ) {
        return new GroundingAssertions(validation);
    }

    public static LifecycleAssertions lifecycle(List<RetrievalHit> hits) {
        return new LifecycleAssertions(hits);
    }

    public static SecurityAssertions security(List<RetrievalHit> hits) {
        return new SecurityAssertions(hits);
    }
}
