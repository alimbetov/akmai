package kz.alimbetov.akmai.knowledge.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.api.CanonicalDocument;
import kz.alimbetov.akmai.knowledge.api.CanonicalKnowledgeDocument;
import kz.alimbetov.akmai.knowledge.model.DocumentMetadata;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDocument;
import kz.alimbetov.akmai.knowledge.model.KnowledgeLanguage;
import kz.alimbetov.akmai.knowledge.model.SemanticUnit;
import kz.alimbetov.akmai.knowledge.model.SemanticUnitType;
import kz.alimbetov.akmai.knowledge.model.SourceProvenanceMetadata;
import kz.alimbetov.akmai.knowledge.model.StructuralRole;
import kz.alimbetov.akmai.knowledge.model.UnitProvenance;
import org.springframework.stereotype.Component;

@Component
public class CanonicalDocumentMapper {

    public PreparedCanonicalDocument prepare(CanonicalDocument source) {
        if (source == null) {
            throw new IllegalArgumentException("canonical document is required");
        }

        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>(source.metadata());
        metadata.put("source", source.source());
        metadata.put(DocumentMetadata.ACCESS_LEVEL, source.accessLevel());
        metadata.put("canonicalVersion", source.version());
        metadata.put("contentFormat", "CANONICAL_BLOCKS");

        List<String> headingStack = new ArrayList<>();
        List<SemanticUnit> units = new ArrayList<>();
        StringBuilder rendered = new StringBuilder();

        for (CanonicalDocument.Block block : source.blocks()) {
            if (block == null) {
                continue;
            }
            appendRendered(rendered, block.text());

            String sectionPath;
            if (block.type() == CanonicalDocument.BlockType.HEADING) {
                updateHeadingStack(
                        headingStack,
                        block.headingLevel(),
                        block.text()
                );
            }
            sectionPath = explicitOrDerived(
                    block.sectionPath(),
                    headingStack,
                    source.title()
            );

            units.add(unit(
                    block.blockId(),
                    block.text(),
                    block.type() == CanonicalDocument.BlockType.HEADING,
                    structuralRole(block.type()),
                    block.pageFrom(),
                    block.pageTo(),
                    sectionPath,
                    boundingBox(block.boundingBox())
            ));
        }

        return prepared(
                source.documentId(),
                source.title(),
                rendered,
                source.language(),
                source.domain(),
                metadata,
                units
        );
    }

    public PreparedCanonicalDocument prepare(
            CanonicalKnowledgeDocument source,
            String canonicalHash
    ) {
        if (source == null) {
            throw new IllegalArgumentException("canonical document is required");
        }

        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>(source.metadata());
        metadata.put(DocumentMetadata.ACCESS_LEVEL, source.accessLevel());
        metadata.put("canonicalVersion", source.version());
        metadata.put("contentFormat", "CANONICAL_BLOCKS_V1");
        SourceProvenanceMetadata.putSource(metadata, source, canonicalHash);

        List<String> headingStack = new ArrayList<>();
        List<SemanticUnit> units = new ArrayList<>();
        StringBuilder rendered = new StringBuilder();

        for (CanonicalKnowledgeDocument.Block block : source.blocks()) {
            appendRendered(rendered, block.text());
            if (block.type() == CanonicalKnowledgeDocument.BlockType.HEADING) {
                updateHeadingStack(
                        headingStack,
                        block.headingLevel(),
                        block.text()
                );
            }
            String sectionPath = explicitOrDerived(
                    block.sectionPath(),
                    headingStack,
                    source.title()
            );
            units.add(unit(
                    block.blockId(),
                    block.text(),
                    block.type() == CanonicalKnowledgeDocument.BlockType.HEADING,
                    structuralRole(block.type()),
                    block.pageFrom(),
                    block.pageTo(),
                    sectionPath,
                    boundingBox(block.boundingBox())
            ));
        }

        return prepared(
                source.documentId(),
                source.title(),
                rendered,
                source.language(),
                source.domain(),
                metadata,
                units
        );
    }

    private PreparedCanonicalDocument prepared(
            String documentId,
            String title,
            StringBuilder rendered,
            String language,
            kz.alimbetov.akmai.knowledge.model.KnowledgeDomain domain,
            Map<String, Object> metadata,
            List<SemanticUnit> units
    ) {
        KnowledgeDocument document = new KnowledgeDocument(
                documentId,
                title,
                rendered.toString(),
                KnowledgeLanguage.parse(language).code(),
                domain,
                Map.copyOf(metadata)
        );
        return new PreparedCanonicalDocument(document, List.copyOf(units));
    }

    private SemanticUnit unit(
            String blockId,
            String text,
            boolean heading,
            StructuralRole structuralRole,
            Integer pageFrom,
            Integer pageTo,
            String sectionPath,
            UnitProvenance.BoundingBox boundingBox
    ) {
        UnitProvenance.BlockRef blockRef = new UnitProvenance.BlockRef(
                blockId,
                pageFrom,
                pageTo,
                sectionPath,
                boundingBox
        );
        return new SemanticUnit(
                text,
                sectionPath,
                heading ? SemanticUnitType.HEADING : SemanticUnitType.PARAGRAPH,
                false,
                structuralRole,
                new UnitProvenance(
                        List.of(blockId),
                        pageFrom,
                        pageTo,
                        sectionPath,
                        List.of(blockRef)
                )
        );
    }

    private void appendRendered(StringBuilder rendered, String text) {
        if (!rendered.isEmpty()) {
            rendered.append("\n\n");
        }
        rendered.append(text);
    }

    private void updateHeadingStack(
            List<String> headingStack,
            Integer headingLevel,
            String text
    ) {
        int level = headingLevel == null ? 1 : headingLevel;
        while (headingStack.size() >= level) {
            headingStack.removeLast();
        }
        headingStack.add(text.trim());
    }

    private UnitProvenance.BoundingBox boundingBox(
            CanonicalDocument.BoundingBox source
    ) {
        if (source == null) {
            return null;
        }
        return new UnitProvenance.BoundingBox(
                source.x(),
                source.y(),
                source.width(),
                source.height()
        );
    }

    private UnitProvenance.BoundingBox boundingBox(
            CanonicalKnowledgeDocument.BoundingBox source
    ) {
        if (source == null) {
            return null;
        }
        return new UnitProvenance.BoundingBox(
                source.x(),
                source.y(),
                source.width(),
                source.height()
        );
    }

    private String explicitOrDerived(
            String explicit,
            List<String> headingStack,
            String fallback
    ) {
        if (explicit != null && !explicit.isBlank()) {
            return explicit.trim();
        }
        if (!headingStack.isEmpty()) {
            return String.join(" > ", headingStack);
        }
        return fallback;
    }

    private String explicitOrDerived(
            List<String> explicit,
            List<String> headingStack,
            String fallback
    ) {
        if (explicit != null && !explicit.isEmpty()) {
            return String.join(" > ", explicit);
        }
        if (!headingStack.isEmpty()) {
            return String.join(" > ", headingStack);
        }
        return fallback;
    }

    private StructuralRole structuralRole(CanonicalDocument.BlockType type) {
        return switch (type) {
            case HEADING -> StructuralRole.HEADING;
            case LIST -> StructuralRole.LIST_ITEM;
            case PARAGRAPH, TABLE, CODE, FOOTNOTE, IMAGE_TEXT ->
                    StructuralRole.PARAGRAPH;
        };
    }

    private StructuralRole structuralRole(
            CanonicalKnowledgeDocument.BlockType type
    ) {
        return switch (type) {
            case HEADING -> StructuralRole.HEADING;
            case LIST -> StructuralRole.LIST_ITEM;
            case PARAGRAPH, TABLE, CODE, FOOTNOTE, IMAGE_TEXT ->
                    StructuralRole.PARAGRAPH;
        };
    }

    public record PreparedCanonicalDocument(
            KnowledgeDocument document,
            List<SemanticUnit> semanticUnits
    ) {
        public PreparedCanonicalDocument {
            semanticUnits = semanticUnits == null
                    ? List.of()
                    : List.copyOf(semanticUnits);
        }
    }
}
