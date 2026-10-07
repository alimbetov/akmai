package kz.alimbetov.akmai.knowledge.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.api.CanonicalDocument;
import kz.alimbetov.akmai.knowledge.model.DocumentMetadata;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDocument;
import kz.alimbetov.akmai.knowledge.model.KnowledgeLanguage;
import kz.alimbetov.akmai.knowledge.model.SemanticUnit;
import kz.alimbetov.akmai.knowledge.model.SemanticUnitType;
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
            if (!rendered.isEmpty()) {
                rendered.append("\n\n");
            }
            rendered.append(block.text());

            String sectionPath;
            if (block.type() == CanonicalDocument.BlockType.HEADING) {
                int level = block.headingLevel() == null ? 1 : block.headingLevel();
                while (headingStack.size() >= level) {
                    headingStack.removeLast();
                }
                headingStack.add(block.text().trim());
                sectionPath = explicitOrDerived(
                        block.sectionPath(),
                        headingStack,
                        source.title()
                );
            } else {
                sectionPath = explicitOrDerived(
                        block.sectionPath(),
                        headingStack,
                        source.title()
                );
            }

            UnitProvenance.BlockRef blockRef = new UnitProvenance.BlockRef(
                    block.blockId(),
                    block.pageFrom(),
                    block.pageTo(),
                    sectionPath,
                    boundingBox(block.boundingBox())
            );
            units.add(new SemanticUnit(
                    block.text(),
                    sectionPath,
                    block.type() == CanonicalDocument.BlockType.HEADING
                            ? SemanticUnitType.HEADING
                            : SemanticUnitType.PARAGRAPH,
                    false,
                    structuralRole(block.type()),
                    new UnitProvenance(
                            List.of(block.blockId()),
                            block.pageFrom(),
                            block.pageTo(),
                            sectionPath,
                            List.of(blockRef)
                    )
            ));
        }

        KnowledgeDocument document = new KnowledgeDocument(
                source.documentId(),
                source.title(),
                rendered.toString(),
                KnowledgeLanguage.parse(source.language()).code(),
                source.domain(),
                Map.copyOf(metadata)
        );
        return new PreparedCanonicalDocument(document, List.copyOf(units));
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

    private StructuralRole structuralRole(CanonicalDocument.BlockType type) {
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
