package kz.alimbetov.akmai.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import kz.alimbetov.akmai.config.ModelBudgetProperties;
import kz.alimbetov.akmai.rag.service.RagPromptTemplate;
import kz.alimbetov.akmai.token.ModelTokenBudgetRegistry;
import kz.alimbetov.akmai.token.Utf8ByteUpperBoundTokenCounter;
import org.junit.jupiter.api.Test;

class ChatTokenBudgetServiceTest {

    @Test
    void budgetIncludesSystemQuestionSerializedContextAndAnswerReserve() {
        Utf8ByteUpperBoundTokenCounter counter =
                new Utf8ByteUpperBoundTokenCounter();
        RagPromptTemplate prompt = new RagPromptTemplate();
        RetrievalProperties properties = properties(256);
        String question = "What is the policy?";
        String context = "{\"sources\":[{\"sourceNumber\":1,\"text\":\"fact\"}]}";

        int input = counter.upperBound(prompt.systemPrompt())
                + counter.upperBound(prompt.userPrompt(question, context));
        int required = input + properties.answerReservedTokens();

        ChatTokenBudgetService exactFit = new ChatTokenBudgetService(
                new ModelTokenBudgetRegistry(
                        counter,
                        new ModelBudgetProperties(
                                Math.max(1024, required),
                                8192
                        )
                ),
                properties,
                prompt
        );
        ChatTokenBudgetService tooSmall = new ChatTokenBudgetService(
                new ModelTokenBudgetRegistry(
                        counter,
                        new ModelBudgetProperties(
                                Math.max(1024, required - 1),
                                8192
                        )
                ),
                properties,
                prompt
        );

        assertThat(exactFit.upperBoundInputTokens(question, context))
                .isEqualTo(input);
        assertThat(exactFit.fits(question, context)).isTrue();
        if (required > 1024) {
            assertThat(tooSmall.fits(question, context)).isFalse();
        }
    }

    private RetrievalProperties properties(int answerReserve) {
        RetrievalProperties defaults = RetrievalTestProperties.defaults();
        return new RetrievalProperties(
                defaults.parallelism(),
                defaults.queueCapacity(),
                defaults.vectorTopK(),
                defaults.vectorSimilarityThreshold(),
                defaults.lexicalLimit(),
                defaults.identifierLimit(),
                defaults.referenceLimit(),
                defaults.rrfK(),
                defaults.expansionSeeds(),
                defaults.expansionRadius(),
                defaults.expansionMax(),
                defaults.contextMaxTokens(),
                defaults.contextMaxChunks(),
                defaults.contextMaxChunksPerDocument(),
                defaults.rerankerEnabled(),
                defaults.rerankerCandidates(),
                defaults.rerankerTimeout(),
                defaults.rerankerFusedWeight(),
                Duration.ofSeconds(8),
                Duration.ofSeconds(3),
                Duration.ofSeconds(20),
                Duration.ofSeconds(3),
                defaults.contextExpansionMaxChunks(),
                answerReserve
        );
    }
}
