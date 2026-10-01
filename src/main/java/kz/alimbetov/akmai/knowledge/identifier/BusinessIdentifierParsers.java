package kz.alimbetov.akmai.knowledge.identifier;

import org.springframework.stereotype.Component;

public final class BusinessIdentifierParsers {

    private BusinessIdentifierParsers() {
    }

    @Component
    public static class ContractNumberParser extends RegexIdentifierParser {
        public ContractNumberParser(IdentifierNormalizer normalizer) {
            super("(?:договор(?:а|у|ом|е|ы|ов)?|contract)\\s*(?:№|no\\.?|number)?\\s*[:#-]?\\s*([\\p{L}0-9][\\p{L}0-9./_-]{2,})", normalizer);
        }
        @Override public IdentifierType type() { return IdentifierType.CONTRACT_NUMBER; }
    }

    @Component
    public static class OrderNumberParser extends RegexIdentifierParser {
        public OrderNumberParser(IdentifierNormalizer normalizer) {
            super("(?:заказ(?:а|у|ом|е|ы|ов)?|order)\\s*(?:№|no\\.?|number)?\\s*[:#-]?\\s*([\\p{L}0-9][\\p{L}0-9./_-]{2,})", normalizer);
        }
        @Override public IdentifierType type() { return IdentifierType.ORDER_NUMBER; }
    }

    @Component
    public static class InvoiceNumberParser extends RegexIdentifierParser {
        public InvoiceNumberParser(IdentifierNormalizer normalizer) {
            super("(?:сч[её]т|invoice)\\s*(?:№|no\\.?|number)?\\s*[:#-]?\\s*([\\p{L}0-9][\\p{L}0-9./_-]{2,})", normalizer);
        }
        @Override public IdentifierType type() { return IdentifierType.INVOICE_NUMBER; }
    }

    @Component
    public static class ApplicationNumberParser extends RegexIdentifierParser {
        public ApplicationNumberParser(IdentifierNormalizer normalizer) {
            super("(?:заявк(?:а|и)|application)\\s*(?:№|no\\.?|number)?\\s*[:#-]?\\s*([\\p{L}0-9][\\p{L}0-9./_-]{2,})", normalizer);
        }
        @Override public IdentifierType type() { return IdentifierType.APPLICATION_NUMBER; }
    }

    @Component
    public static class CaseNumberParser extends RegexIdentifierParser {
        public CaseNumberParser(IdentifierNormalizer normalizer) {
            super("(?:дел(?:о|а|у|ом|е)|case)\\s*(?:№|no\\.?|number)?\\s*[:#-]?\\s*([\\p{L}0-9][\\p{L}0-9./_-]{2,})", normalizer);
        }
        @Override public IdentifierType type() { return IdentifierType.CASE_NUMBER; }
    }

    @Component
    public static class DocumentNumberParser extends RegexIdentifierParser {
        public DocumentNumberParser(IdentifierNormalizer normalizer) {
            super("(?:документ(?:а|у|ом|е|ы|ов)?|document)\\s*(?:№|no\\.?|number)?\\s*[:#-]?\\s*([\\p{L}0-9][\\p{L}0-9./_-]{2,})", normalizer);
        }
        @Override public IdentifierType type() { return IdentifierType.DOCUMENT_NUMBER; }
    }
}
