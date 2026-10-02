package kz.alimbetov.akmai.knowledge.identifier;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class IdentifierCapabilityRegistry {

    private final Set<IdentifierType> supported;

    public IdentifierCapabilityRegistry(List<IdentifierParser> parsers) {
        EnumSet<IdentifierType> discovered =
                EnumSet.noneOf(IdentifierType.class);
        parsers.forEach(parser -> discovered.add(parser.type()));
        this.supported = Set.copyOf(discovered);
    }

    public Set<IdentifierType> supportedTypes() {
        return supported;
    }

    public boolean isSupported(IdentifierType type) {
        return supported.contains(type);
    }
}
