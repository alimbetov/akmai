package kz.alimbetov.akmai.knowledge.ingestion;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfile;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfileService;
import kz.alimbetov.akmai.knowledge.embedding.GenerationEmbeddingService;
import kz.alimbetov.akmai.knowledge.api.KnowledgeIngestionResponse;
import kz.alimbetov.akmai.knowledge.identifier.DocumentIdentifier;
import kz.alimbetov.akmai.knowledge.idempotency.IngestionIdempotencyContext;
import kz.alimbetov.akmai.knowledge.idempotency.IngestionIdempotencyRepository;
import kz.alimbetov.akmai.knowledge.lifecycle.DocumentGenerationRepository;
import kz.alimbetov.akmai.knowledge.lifecycle.GenerationIdentity;
import kz.alimbetov.akmai.knowledge.lifecycle.RetentionPolicy;
import kz.alimbetov.akmai.knowledge.lifecycle.RetentionProperties;
import kz.alimbetov.akmai.knowledge.lifecycle.VectorGenerationRepository.VectorGenerationEntry;
import kz.alimbetov.akmai.knowledge.projection.SearchProjection;
import kz.alimbetov.akmai.knowledge.projection.SearchProjectionFactory;
import kz.alimbetov.akmai.knowledge.vector.PostgresGenerationVectorRepository.VectorRow;
import kz.alimbetov.akmai.observability.AkmaiMetrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class PersistenceCoordinator {

    private final SearchProjectionFactory projectionFactory;
    private final DocumentGenerationRepository generationRepository;
    private final EmbeddingProfileService profileService;
    private final GenerationEmbeddingService embeddingService;
    private final GenerationPublicationService publicationService;
    private final PublicationOutcomeResolver publicationOutcomeResolver;
    private final IngestionIdempotencyRepository idempotencyRepository;
    private final RetentionProperties retentionProperties;
    private AkmaiMetrics metrics;

    public PersistenceCoordinator(
            SearchProjectionFactory projectionFactory,
            DocumentGenerationRepository generationRepository,
            EmbeddingProfileService profileService,
            GenerationEmbeddingService embeddingService,
            GenerationPublicationService publicationService,
            PublicationOutcomeResolver publicationOutcomeResolver,
            IngestionIdempotencyRepository idempotencyRepository,
            RetentionProperties retentionProperties
    ) {
        this.projectionFactory = projectionFactory;
        this.generationRepository = generationRepository;
        this.profileService = profileService;
        this.embeddingService = embeddingService;
        this.publicationService = publicationService;
        this.publicationOutcomeResolver = publicationOutcomeResolver;
        this.idempotencyRepository = idempotencyRepository;
        this.retentionProperties = retentionProperties;
    }

    @Autowired(required = false)
    void setMetrics(AkmaiMetrics metrics) {
        this.metrics = metrics;
    }

    public void persist(
            List<EnrichedKnowledgeChunk> chunks,
            IngestionIdempotencyContext idempotency,
            KnowledgeIngestionResponse response,
            long accessLevel
    ) {
        if (accessLevel <= 0) {
            throw new IllegalArgumentException("accessLevel must be positive");
        }
        if (chunks == null || chunks.isEmpty()) {
            return;
        }

        List<SearchProjection> baseProjections = chunks.stream()
                .map(projectionFactory::create)
                .toList();
        String documentId = singleDocumentId(baseProjections);

        profileService.assertConfiguredProfileIsActive();
        EmbeddingProfile profile = profileService.activeProfile();
        String contentFingerprint = fingerprint(baseProjections);

        long generation = generationRepository.allocate(
                documentId,
                retentionProperties.defaultPolicy(),
                expiration(),
                profile.profileId(),
                contentFingerprint,
                accessLevel
        );
        idempotencyRepository.attachGeneration(idempotency, generation);

        GenerationIdentity identity = new GenerationIdentity(
                documentId,
                generation,
                accessLevel
        );

        long startedNanos = System.nanoTime();
        try {
            List<SearchProjection> projections = baseProjections.stream()
                    .map(value -> value.withIdentity(identity))
                    .toList();
            List<float[]> embeddings = embeddingService.embed(projections, profile);
            List<DocumentIdentifier> identifiers = identifiers(projections);
            List<VectorGenerationEntry> manifest = manifest(projections, generation);
            List<VectorRow> vectors = vectors(
                    projections,
                    embeddings,
                    profile,
                    generation
            );

            GenerationPublicationService.PublicationResult result;
            try {
                result = publicationService.publish(
                        documentId,
                        generation,
                        retentionProperties.defaultPolicy(),
                        expiration(),
                        profile,
                        projections,
                        identifiers,
                        manifest,
                        vectors,
                        idempotency,
                        response
                );
            } catch (RuntimeException publicationFailure) {
                PublicationOutcomeResolver.Outcome outcome;
                try {
                    outcome = publicationOutcomeResolver.resolve(
                            documentId,
                            generation,
                            idempotency
                    );
                } catch (RuntimeException resolutionFailure) {
                    publicationFailure.addSuppressed(resolutionFailure);
                    throw new PublicationOutcomeUnknownException(
                            "Publication outcome could not be determined",
                            publicationFailure
                    );
                }
                if (outcome == PublicationOutcomeResolver.Outcome.COMMITTED) {
                    result = GenerationPublicationService.PublicationResult.PUBLISHED;
                } else if (outcome
                        == PublicationOutcomeResolver.Outcome.SUPERSEDED) {
                    result = GenerationPublicationService.PublicationResult.SUPERSEDED;
                } else {
                    throw publicationFailure;
                }
            }
            if (result == GenerationPublicationService.PublicationResult.SUPERSEDED) {
                throw new IllegalStateException(
                        "Generation was superseded by a newer publication"
                );
            }
            if (metrics != null) {
                metrics.ingestion(
                        "success",
                        java.time.Duration.ofNanos(
                                System.nanoTime() - startedNanos
                        ),
                        chunks.size()
                );
            }
        } catch (PublicationOutcomeUnknownException exception) {
            if (metrics != null) {
                metrics.ingestion(
                        "unknown",
                        java.time.Duration.ofNanos(
                                System.nanoTime() - startedNanos
                        ),
                        chunks.size()
                );
            }
            throw exception;
        } catch (RuntimeException exception) {
            if (metrics != null) {
                metrics.ingestion(
                        "failure",
                        java.time.Duration.ofNanos(
                                System.nanoTime() - startedNanos
                        ),
                        chunks.size()
                );
            }
            generationRepository.fail(
                    documentId,
                    generation,
                    "INGESTION_FAILED",
                    safeMessage(exception)
            );
            idempotencyRepository.fail(idempotency, safeMessage(exception));
            throw exception;
        }
    }

    private String singleDocumentId(List<SearchProjection> projections) {
        List<String> documentIds = projections.stream()
                .map(SearchProjection::documentId)
                .distinct()
                .toList();
        if (documentIds.size() != 1) {
            throw new IllegalArgumentException(
                    "A persistence batch must contain exactly one document"
            );
        }
        return documentIds.getFirst();
    }

    private List<DocumentIdentifier> identifiers(
            List<SearchProjection> projections
    ) {
        Instant createdAt = Instant.now();
        return projections.stream()
                .flatMap(projection -> projection.identifiers().stream()
                        .map(identifier -> new DocumentIdentifier(
                                projection.documentId(),
                                projection.generation(),
                                projection.chunkId(),
                                pageNumber(projection),
                                identifier.type(),
                                identifier.rawValue(),
                                identifier.normalizedValue(),
                                identifier.contextText(),
                                createdAt
                        )))
                .toList();
    }

    private List<VectorGenerationEntry> manifest(
            List<SearchProjection> projections,
            long generation
    ) {
        return projections.stream()
                .map(projection -> new VectorGenerationEntry(
                        VectorIdentity.physicalId(
                                projection.documentId(),
                                generation,
                                projection.chunkId()
                        ),
                        projection.chunkId()
                ))
                .toList();
    }

    private List<VectorRow> vectors(
            List<SearchProjection> projections,
            List<float[]> embeddings,
            EmbeddingProfile profile,
            long generation
    ) {
        if (projections.size() != embeddings.size()) {
            throw new IllegalStateException("Embedding count mismatch");
        }
        java.util.ArrayList<VectorRow> result =
                new java.util.ArrayList<>(projections.size());
        for (int i = 0; i < projections.size(); i++) {
            SearchProjection projection = projections.get(i);
            String vectorId = VectorIdentity.physicalId(
                    projection.documentId(),
                    generation,
                    projection.chunkId()
            );
            result.add(new VectorRow(
                    vectorId,
                    projection.embeddingText(),
                    vectorMetadata(projection, profile, generation),
                    embeddings.get(i)
            ));
        }
        return List.copyOf(result);
    }

    private Map<String, Object> vectorMetadata(
            SearchProjection projection,
            EmbeddingProfile profile,
            long generation
    ) {
        Map<String, Object> metadata = new HashMap<>();
        projection.metadata().forEach((key, value) -> {
            if (key != null
                    && value != null
                    && !key.toLowerCase(java.util.Locale.ROOT).startsWith("akmai")) {
                metadata.put(key, value);
            }
        });
        metadata.put("akmaiMetadataVersion", 2);
        metadata.put("akmaiDocumentId", projection.documentId());
        metadata.put("akmaiGeneration", generation);
        metadata.put("akmaiEmbeddingProfileId", profile.profileId());
        metadata.put("akmaiChunkId", projection.chunkId());
        metadata.put("language", projection.language());
        metadata.put("domain", projection.domain().name());
        metadata.put("sectionPath", projection.sectionPath() == null
                ? ""
                : projection.sectionPath());
        metadata.put("chunkIndex", projection.chunkIndex());
        return Map.copyOf(metadata);
    }

    private Instant expiration() {
        if (retentionProperties.defaultPolicy() == RetentionPolicy.PERMANENT) {
            return null;
        }
        return Instant.now().plus(retentionProperties.defaultTtl());
    }

    private int pageNumber(SearchProjection projection) {
        Object page = projection.metadata().get("pageFrom");
        if (!(page instanceof Number)) {
            page = projection.metadata().get("pageNumber");
        }
        if (!(page instanceof Number)) {
            page = projection.metadata().get("page");
        }
        return page instanceof Number number ? number.intValue() : 0;
    }

    private String fingerprint(List<SearchProjection> projections) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (SearchProjection projection : projections) {
                update(digest, projection.documentId());
                update(digest, projection.chunkId());
                update(digest, projection.text());
                update(digest, projection.embeddingText());
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private void update(MessageDigest digest, String value) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(buffer)) {
                out.writeInt(bytes.length);
                out.write(bytes);
            }
            digest.update(buffer.toByteArray());
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot calculate content fingerprint", exception);
        }
    }

    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        String value = exception.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
        value = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }
}
