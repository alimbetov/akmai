package kz.alimbetov.akmai.knowledge.lifecycle;

import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfile;
import kz.alimbetov.akmai.knowledge.identifier.DocumentIdentifierRepository;
import kz.alimbetov.akmai.knowledge.projection.SearchProjectionRepository;
import kz.alimbetov.akmai.knowledge.reference.ReferenceGraphRepository;
import kz.alimbetov.akmai.knowledge.vector.PostgresGenerationVectorRepository;
import org.springframework.stereotype.Service;

@Service
public class GenerationRepairService {

    private final PostgresGenerationVectorRepository vectors;
    private final ReferenceGraphRepository references;
    private final DocumentIdentifierRepository identifiers;
    private final SearchProjectionRepository projections;
    private final VectorGenerationRepository manifests;

    public GenerationRepairService(
            PostgresGenerationVectorRepository vectors,
            ReferenceGraphRepository references,
            DocumentIdentifierRepository identifiers,
            SearchProjectionRepository projections,
            VectorGenerationRepository manifests
    ) {
        this.vectors = vectors;
        this.references = references;
        this.identifiers = identifiers;
        this.projections = projections;
        this.manifests = manifests;
    }

    public void repair(
            EmbeddingProfile profile,
            GenerationIdentity identity
    ) {
        vectors.deleteGeneration(profile, identity);
        references.deleteGeneration(identity);
        identifiers.deleteGeneration(identity);
        projections.deleteGeneration(identity);
        manifests.deleteGeneration(identity);
    }
}
