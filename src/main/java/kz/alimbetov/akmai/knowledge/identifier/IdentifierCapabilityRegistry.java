package kz.alimbetov.akmai.knowledge.identifier;

import java.util.EnumSet;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class IdentifierCapabilityRegistry {

    private static final Set<IdentifierType> SUPPORTED = EnumSet.of(
            IdentifierType.CONTRACT_NUMBER,
            IdentifierType.DOCUMENT_NUMBER,
            IdentifierType.ORDER_NUMBER,
            IdentifierType.INVOICE_NUMBER,
            IdentifierType.APPLICATION_NUMBER,
            IdentifierType.CASE_NUMBER
    );

    public Set<IdentifierType> supportedTypes() {
        return Set.copyOf(SUPPORTED);
    }

    public boolean isSupported(IdentifierType type) {
        return SUPPORTED.contains(type);
    }
}
