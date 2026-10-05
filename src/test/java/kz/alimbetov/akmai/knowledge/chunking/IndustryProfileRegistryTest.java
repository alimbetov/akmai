package kz.alimbetov.akmai.knowledge.chunking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDocument;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import org.junit.jupiter.api.Test;

class IndustryProfileRegistryTest {

    private final IndustryProfileRegistry registry =
            new IndustryProfileRegistry(
                    new YamlIndustryProfileLoader().loadAll()
            );

    @Test
    void loadsVersionedDataDrivenProfiles() {
        assertThat(registry.profileIds())
                .containsExactlyInAnyOrder(
                        "civil_law",
                        "cardiology",
                        "software",
                        "banking",
                        "pharmacology",
                        "cybersecurity",
                        "public_administration"
                );
    }

    @Test
    void composesCivilLawWithGenericLegalHierarchy() {
        IndustryProfile profile = registry.forDocument(document(
                KnowledgeDomain.LEGAL,
                "civil_law"
        ));

        assertThat(profile.code().id()).isEqualTo("civil_law");
        assertThat(profile.matchHeading(
                "ARTICLE 5. Duties",
                LanguageProfiles.forCode("en")
        )).isPresent();
    }

    @Test
    void explicitUnknownIndustryCodeFailsClosed() {
        assertThatThrownBy(() -> registry.forDocument(document(
                KnowledgeDomain.GENERAL,
                "unknown_sector"
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported industryCode");
    }

    @Test
    void incompatibleDomainFailsClosed() {
        assertThatThrownBy(() -> registry.forDocument(document(
                KnowledgeDomain.MEDICAL,
                "software"
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("incompatible");
    }

    private KnowledgeDocument document(
            KnowledgeDomain domain,
            String industryCode
    ) {
        return new KnowledgeDocument(
                "doc",
                "Title",
                "Body",
                "en",
                domain,
                Map.of("industryCode", industryCode)
        );
    }
}
