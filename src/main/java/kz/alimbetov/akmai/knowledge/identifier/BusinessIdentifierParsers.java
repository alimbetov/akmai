package kz.alimbetov.akmai.knowledge.identifier;

import org.springframework.stereotype.Component;

public final class BusinessIdentifierParsers {

    private BusinessIdentifierParsers() {
    }

    private static String pattern(String term) {
        String value = "[\\p{L}0-9][\\p{L}0-9./_-]{1,499}";
        String shaped = "(?=[\\p{L}0-9./_-]*\\d)" + value;
        return "(?:" + term + ")\\s*(?:"
                + "(?:№|no\\.?|number)\\s*[:#-]?\\s*(" + value + ")"
                + "|[:#-]\\s*(" + value + ")"
                + "|(" + shaped + ")"
                + ")(?![\\p{L}0-9./_-])";
    }

    @Component
    public static class ContractNumberParser extends RegexIdentifierParser {
        public ContractNumberParser(IdentifierNormalizer normalizer) {
            super(pattern("договор(?:а|у|ом|е|ы|ов)?|contract"), normalizer);
        }

        @Override
        public IdentifierType type() {
            return IdentifierType.CONTRACT_NUMBER;
        }
    }

    @Component
    public static class OrderNumberParser extends RegexIdentifierParser {
        public OrderNumberParser(IdentifierNormalizer normalizer) {
            super(pattern("заказ(?:а|у|ом|е|ы|ов)?|order"), normalizer);
        }

        @Override
        public IdentifierType type() {
            return IdentifierType.ORDER_NUMBER;
        }
    }

    @Component
    public static class InvoiceNumberParser extends RegexIdentifierParser {
        public InvoiceNumberParser(IdentifierNormalizer normalizer) {
            super(pattern("сч[её]т|invoice"), normalizer);
        }

        @Override
        public IdentifierType type() {
            return IdentifierType.INVOICE_NUMBER;
        }
    }

    @Component
    public static class ApplicationNumberParser extends RegexIdentifierParser {
        public ApplicationNumberParser(IdentifierNormalizer normalizer) {
            super(pattern("заявк(?:а|и)|application"), normalizer);
        }

        @Override
        public IdentifierType type() {
            return IdentifierType.APPLICATION_NUMBER;
        }
    }

    @Component
    public static class CaseNumberParser extends RegexIdentifierParser {
        public CaseNumberParser(IdentifierNormalizer normalizer) {
            super(pattern("дел(?:о|а|у|ом|е)|case"), normalizer);
        }

        @Override
        public IdentifierType type() {
            return IdentifierType.CASE_NUMBER;
        }
    }

    @Component
    public static class DocumentNumberParser extends RegexIdentifierParser {
        public DocumentNumberParser(IdentifierNormalizer normalizer) {
            super(pattern("документ(?:а|у|ом|е|ы|ов)?|document"), normalizer);
        }

        @Override
        public IdentifierType type() {
            return IdentifierType.DOCUMENT_NUMBER;
        }
    }
}
