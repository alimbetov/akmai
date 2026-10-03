package kz.alimbetov.akmai.knowledge.chunking;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDocument;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class IndustryProfileRegistry {

    private static final Set<String> RESERVED_IDS = Set.of(
            "general_generic",
            "legal_generic",
            "medical_generic"
    );

    private final Map<String, IndustryProfile> byId;

    @Autowired
    public IndustryProfileRegistry(
            YamlIndustryProfileLoader loader
    ) {
        this(loader.loadAll());
    }

    IndustryProfileRegistry(List<IndustryProfile> profiles) {
        Map<String, IndustryProfile> map = new LinkedHashMap<>();
        for (IndustryProfile profile : profiles) {
            String id = profile.code().id();
            if (RESERVED_IDS.contains(id)) {
                throw new IllegalArgumentException(
                        "Reserved industry profile id: " + id
                );
            }
            if (map.putIfAbsent(id, profile) != null) {
                throw new IllegalArgumentException(
                        "Duplicate industry profile id: " + id
                );
            }
        }
        this.byId = Map.copyOf(map);
    }

    public IndustryProfile forDocument(KnowledgeDocument document) {
        if (document == null) {
            throw new IllegalArgumentException(
                    "document must not be null"
            );
        }

        IndustryProfile base =
                IndustryProfiles.defaultFor(document.domain());
        String industryId = industryId(document);
        if (industryId == null) {
            return base;
        }

        IndustryProfile specialized = byId.get(industryId);
        if (specialized == null) {
            throw new IllegalArgumentException(
                    "Unsupported industryCode: " + industryId
            );
        }
        if (!compatible(document.domain(), specialized.code().domain())) {
            throw new IllegalArgumentException(
                    "industryCode " + industryId
                            + " is incompatible with domain "
                            + document.domain()
            );
        }
        return IndustryProfiles.compose(base, specialized);
    }

    public Set<String> profileIds() {
        return byId.keySet();
    }

    private String industryId(KnowledgeDocument document) {
        if (document.metadata() == null) {
            return null;
        }
        Object raw = document.metadata().get("industryCode");
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof String value)) {
            throw new IllegalArgumentException(
                    "industryCode metadata must be a string"
            );
        }
        String normalized = value.trim()
                .toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? null : normalized;
    }

    private boolean compatible(
            KnowledgeDomain documentDomain,
            IndustryDomain industryDomain
    ) {
        if (industryDomain == IndustryDomain.LEGAL) {
            return documentDomain == KnowledgeDomain.LEGAL;
        }
        if (industryDomain == IndustryDomain.MEDICAL) {
            return documentDomain == KnowledgeDomain.MEDICAL;
        }
        return documentDomain == KnowledgeDomain.GENERAL;
    }
}
