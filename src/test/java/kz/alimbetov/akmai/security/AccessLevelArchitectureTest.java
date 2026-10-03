package kz.alimbetov.akmai.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import kz.alimbetov.akmai.knowledge.api.AddKnowledgeRequest;
import kz.alimbetov.akmai.knowledge.identifier.DocumentIdentifierRepository;
import kz.alimbetov.akmai.knowledge.identifier.search.IdentifierSearchIndex;
import kz.alimbetov.akmai.knowledge.identifier.search.IdentifierSearchQuery;
import kz.alimbetov.akmai.knowledge.ingestion.PersistenceCoordinator;
import kz.alimbetov.akmai.knowledge.lifecycle.DocumentGenerationRepository;
import kz.alimbetov.akmai.knowledge.lifecycle.DocumentLifecycleRepository;
import kz.alimbetov.akmai.knowledge.projection.PublishedSearchProjectionReader;
import kz.alimbetov.akmai.knowledge.projection.SearchProjectionRepository;
import kz.alimbetov.akmai.knowledge.reference.ReferenceGraphRepository;
import kz.alimbetov.akmai.knowledge.vector.PublishedVectorSearchRepository;
import kz.alimbetov.akmai.rag.api.QuestionRequest;
import kz.alimbetov.akmai.rag.retrieval.KnowledgeExpansion;
import kz.alimbetov.akmai.rag.retrieval.ParallelRetrievalExecutor;
import kz.alimbetov.akmai.rag.retrieval.ResultFusion;
import kz.alimbetov.akmai.rag.retrieval.RetrievalContext;
import kz.alimbetov.akmai.rag.service.RagQuestionService;
import org.junit.jupiter.api.Test;

class AccessLevelArchitectureTest {

    @Test
    void publishedProjectionReadsAlwaysRequireAccessScope() {
        assertThat(PublishedSearchProjectionReader.class.getDeclaredMethods())
                .isNotEmpty()
                .allSatisfy(this::requiresSetParameter);

        assertThat(Arrays.stream(PublishedSearchProjectionReader.class.getMethods())
                .map(Method::getName))
                .doesNotContain(
                        "findByChunkIds",
                        "findChunkIdsByDocumentId",
                        "findGeneration",
                        "findChunkIds"
                );

        assertThat(Arrays.stream(new Class<?>[] {
                ResultFusion.class,
                KnowledgeExpansion.class,
                kz.alimbetov.akmai.rag.retrieval.LexicalRetrievalStrategy.class,
                kz.alimbetov.akmai.rag.retrieval.IdentifierRetrievalStrategy.class,
                kz.alimbetov.akmai.rag.retrieval.ReferenceRetrievalStrategy.class
        })).allSatisfy(type ->
                assertThat(Arrays.stream(type.getDeclaredConstructors())
                        .flatMap(constructor ->
                                Arrays.stream(constructor.getParameterTypes())))
                        .as("%s must not depend on maintenance projection repository", type)
                        .doesNotContain(SearchProjectionRepository.class)
        );
    }

    @Test
    void vectorIdentifierAndReferenceRetrievalAlwaysRequireAccessScope() {
        assertThat(methods(
                PublishedVectorSearchRepository.class,
                method -> method.getName().equals("search")
                        && Modifier.isPublic(method.getModifiers())
        )).isNotEmpty()
                .allSatisfy(this::requiresSetParameter);

        assertThat(methods(
                DocumentIdentifierRepository.class,
                method -> Set.of(
                        "findExact",
                        "findPrefix",
                        "findPartial"
                ).contains(method.getName())
                        && Modifier.isPublic(method.getModifiers())
        )).isNotEmpty()
                .allSatisfy(this::requiresSetParameter);

        assertThat(methods(
                ReferenceGraphRepository.class,
                method -> method.getName().equals("resolveSameDocumentTargets")
                        && Modifier.isPublic(method.getModifiers())
        )).isNotEmpty()
                .allSatisfy(this::requiresSetParameter);

        assertThat(methods(
                IdentifierSearchIndex.class,
                method -> method.getName().equals("search")
        )).allSatisfy(method -> assertThat(method.getParameterTypes())
                .containsExactly(IdentifierSearchQuery.class));
    }

    @Test
    void ragPipelineCannotBeInvokedWithoutAccessScope() {
        assertThat(methods(
                ParallelRetrievalExecutor.class,
                method -> Set.of("execute", "executeDetailed")
                        .contains(method.getName())
                        && Modifier.isPublic(method.getModifiers())
        )).isNotEmpty()
                .allSatisfy(this::requiresSetParameter);

        assertThat(methods(
                ResultFusion.class,
                method -> method.getName().equals("fuse")
                        && Modifier.isPublic(method.getModifiers())
        )).allSatisfy(this::requiresSetParameter);

        assertThat(methods(
                KnowledgeExpansion.class,
                method -> method.getName().equals("expand")
                        && Modifier.isPublic(method.getModifiers())
        )).allSatisfy(this::requiresSetParameter);

        assertThat(methods(
                RagQuestionService.class,
                method -> method.getName().equals("ask")
                        && Modifier.isPublic(method.getModifiers())
        )).allSatisfy(this::requiresSetParameter);

        assertThat(Arrays.stream(RetrievalContext.class.getDeclaredConstructors()))
                .allSatisfy(constructor -> assertConstructorRequiresSet(
                        constructor,
                        2
                ));
    }

    @Test
    void ingestionAndQuestionContractsRequireExplicitAccessLevel() {
        assertThat(methods(
                PersistenceCoordinator.class,
                method -> method.getName().equals("persist")
                        && Modifier.isPublic(method.getModifiers())
        )).isNotEmpty()
                .allSatisfy(this::requiresPrimitiveLongParameter);

        assertThat(methods(
                DocumentGenerationRepository.class,
                method -> method.getName().equals("allocate")
                        && Modifier.isPublic(method.getModifiers())
        )).isNotEmpty()
                .allSatisfy(this::requiresPrimitiveLongParameter);

        assertThat(methods(
                DocumentLifecycleRepository.class,
                method -> method.getName().equals("beginIngestion")
        )).isNotEmpty()
                .allSatisfy(this::requiresPrimitiveLongParameter);

        assertThat(Arrays.stream(AddKnowledgeRequest.class.getDeclaredConstructors()))
                .allSatisfy(constructor -> {
                    assertThat(constructor.getParameterCount()).isEqualTo(8);
                    assertThat(constructor.getParameterTypes()[6])
                            .isEqualTo(Long.class);
                });

        assertThat(Arrays.stream(QuestionRequest.class.getDeclaredConstructors()))
                .allSatisfy(constructor -> assertConstructorRequiresSet(
                        constructor,
                        2
                ));

        assertThat(Arrays.stream(
                IdentifierSearchQuery.class.getDeclaredConstructors()
        )).allSatisfy(constructor -> assertConstructorRequiresSet(
                constructor,
                5
        ));
    }

    @Test
    void partitionRoutingNeverUsesArrayPredicateOnAccessLevel()
            throws Exception {
        for (String source : List.of(
                "src/main/java/kz/alimbetov/akmai/knowledge/vector/"
                        + "PublishedVectorSearchRepository.java",
                "src/main/java/kz/alimbetov/akmai/knowledge/projection/"
                        + "PostgresSearchProjectionRepository.java",
                "src/main/java/kz/alimbetov/akmai/knowledge/identifier/"
                        + "DocumentIdentifierRepository.java",
                "src/main/java/kz/alimbetov/akmai/knowledge/reference/"
                        + "ReferenceGraphRepository.java"
        )) {
            String text = Files.readString(Path.of(source))
                    .replaceAll("\\s+", " ")
                    .toLowerCase();

            assertThat(text)
                    .as("%s must not route ACL partitions with ANY", source)
                    .doesNotContain("access_level = any")
                    .doesNotContain("access_level=any");
        }
    }

    @Test
    void vectorStorageHasNoLegacySchemaFallback() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/kz/alimbetov/akmai/knowledge/vector/"
                        + "PostgresGenerationVectorRepository.java"
        ));

        assertThat(source)
                .doesNotContain(
                        "hasTypedRouting",
                        "insertLegacy",
                        "findIdsByGenerationMetadata",
                        "metadata->>'akmaiDocumentId'"
                );
    }

    @Test
    void projectionReadsRequirePhysicalAccessLevel() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/kz/alimbetov/akmai/knowledge/projection/"
                        + "PostgresSearchProjectionRepository.java"
        ));

        assertThat(source)
                .doesNotContain("resolved_access_level")
                .contains("rs.getLong(\"access_level\")");
    }

    private Method[] methods(
            Class<?> type,
            Predicate<Method> predicate
    ) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(predicate)
                .toArray(Method[]::new);
    }

    private void requiresSetParameter(Method method) {
        assertThat(Arrays.asList(method.getParameterTypes()))
                .as("%s must require an access scope", method)
                .contains(Set.class);
    }

    private void requiresPrimitiveLongParameter(Method method) {
        assertThat(Arrays.asList(method.getParameterTypes()))
                .as("%s must require accessLevel", method)
                .contains(long.class);
    }

    private void assertConstructorRequiresSet(
            Constructor<?> constructor,
            int parameterCount
    ) {
        assertThat(constructor.getParameterCount())
                .as("%s must not expose an unscoped overload", constructor)
                .isEqualTo(parameterCount);
        assertThat(Arrays.asList(constructor.getParameterTypes()))
                .as("%s must require an access scope", constructor)
                .contains(Set.class);
    }
}
