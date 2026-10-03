package kz.alimbetov.akmai.knowledge.chunking;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import kz.alimbetov.akmai.knowledge.model.SemanticUnitType;

public final class IndustryProfiles {

    private static final IndustryProfile GENERAL =
            new EmptyIndustryProfile(
                    new IndustryCode(
                            "general_generic",
                            IndustryDomain.GENERAL,
                            Map.of("en", "General")
                    )
            );

    private static final IndustryProfile MEDICAL =
            new EmptyIndustryProfile(
                    new IndustryCode(
                            "medical_generic",
                            IndustryDomain.MEDICAL,
                            Map.of("en", "General medicine")
                    )
            );

    private static final IndustryProfile LEGAL =
            new GenericLegalIndustryProfile();

    private IndustryProfiles() {
    }

    public static IndustryProfile defaultFor(
            KnowledgeDomain domain
    ) {
        if (domain == null) {
            return GENERAL;
        }
        return switch (domain) {
            case LEGAL -> LEGAL;
            case MEDICAL -> MEDICAL;
            case GENERAL -> GENERAL;
        };
    }

    public static IndustryProfile compose(
            IndustryProfile base,
            IndustryProfile specialized
    ) {
        if (base == null) {
            return specialized;
        }
        if (specialized == null) {
            return base;
        }
        return new CompositeIndustryProfile(base, specialized);
    }

    private static final class CompositeIndustryProfile
            implements IndustryProfile {

        private final IndustryProfile base;
        private final IndustryProfile specialized;

        private CompositeIndustryProfile(
                IndustryProfile base,
                IndustryProfile specialized
        ) {
            this.base = base;
            this.specialized = specialized;
        }

        @Override
        public IndustryCode code() {
            return specialized.code();
        }

        @Override
        public Optional<HeadingMatch> matchHeading(
                String line,
                LanguageProfile language
        ) {
            Optional<HeadingMatch> specializedMatch =
                    specialized.matchHeading(line, language);
            return specializedMatch.isPresent()
                    ? specializedMatch
                    : base.matchHeading(line, language);
        }

        @Override
        public Optional<SemanticUnitType> classifyType(
                String text,
                LanguageProfile language
        ) {
            Optional<SemanticUnitType> specializedType =
                    specialized.classifyType(text, language);
            return specializedType.isPresent()
                    ? specializedType
                    : base.classifyType(text, language);
        }
    }

    private static final class EmptyIndustryProfile
            implements IndustryProfile {

        private final IndustryCode code;

        private EmptyIndustryProfile(IndustryCode code) {
            this.code = code;
        }

        @Override
        public IndustryCode code() {
            return code;
        }

        @Override
        public Optional<HeadingMatch> matchHeading(
                String line,
                LanguageProfile language
        ) {
            return Optional.empty();
        }
    }

    private static final class GenericLegalIndustryProfile
            implements IndustryProfile {

        private static final Pattern LEGAL_HEADING = Pattern.compile(
                "^(LAW|ЗАКОН|ЗАҢ|法律|GESETZ|LOI|LEY|LEI|LEGGE|KANUN|ΝΟΜΟΣ"
                        + "|PART|ЧАСТЬ|БӨЛІМ|编|TEIL|PARTIE|PARTE|KISIM|ΜΕΡΟΣ"
                        + "|CHAPTER|ГЛАВА|ТАРАУ|章|KAPITEL|CHAPITRE|CAPÍTULO"
                        + "|CAPITULO|CAPITOLO|CAPO|BÖLÜM|ΚΕΦΑΛΑΙΟ"
                        + "|SECTION|РАЗДЕЛ|БӨЛІК|节|ABSCHNITT|SECCIÓN|SECCION"
                        + "|SEÇÃO|SECAO|SECÇÃO|SECCAO|SEZIONE|ΤΜΗΜΑ"
                        + "|ARTICLE|СТАТЬЯ|БАП|条|ARTIKEL|ARTÍCULO|ARTICULO"
                        + "|ARTIGO|ARTICOLO|MADDE|ΆΡΘΡΟ|ΑΡΘΡΟ"
                        + "|PARAGRAPH|ПАРАГРАФ|ТАРМАҚ|款|ABSATZ|PARAGRAPHE"
                        + "|PÁRRAFO|PARRAFO|PARÁGRAFO|PARAGRAFO|COMMA"
                        + "|FIKRA|ΠΑΡΑΓΡΑΦΟΣ"
                        + "|SUBPARAGRAPH|ПОДПАРАГРАФ|ТАРМАҚША|项|UNTERABSATZ"
                        + "|ALINÉA|ALINEA|APARTADO|INCISO|ALÍNEA|BENT"
                        + "|ΕΔΆΦΙΟ|ΕΔΑΦΙΟ)(?=\\s|$|[.:：]).*$",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
        );

        private static final Pattern KAZAKH_NUMBERED =
                Pattern.compile(
                        "^\\d+(?:\\.\\d+)*[-‑–—]?\\s*"
                                + "(БӨЛІМ|ТАРАУ|БӨЛІК|БАП|ТАРМАҚША|ТАРМАҚ)"
                                + "(?=\\s|$|[.:：]).*$",
                        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
                );

        private static final Pattern CHINESE_ORDINAL =
                Pattern.compile(
                        "^第[一二三四五六七八九十百千万零〇两\\d]+"
                                + "(编|章|节|条|款|项).*$"
                );

        private static final Pattern NUMBERED_LINE =
                Pattern.compile("^\\d+(?:\\.\\d+)*[.)]?\\s+.+$");

        private static final IndustryCode CODE =
                new IndustryCode(
                        "legal_generic",
                        IndustryDomain.LEGAL,
                        Map.of("en", "General legal")
                );

        @Override
        public IndustryCode code() {
            return CODE;
        }

        @Override
        public Optional<HeadingMatch> matchHeading(
                String line,
                LanguageProfile language
        ) {
            Matcher legal = LEGAL_HEADING.matcher(line);
            if (legal.matches()) {
                return Optional.of(
                        new HeadingMatch(
                                legalLevel(legal.group(1)),
                                line
                        )
                );
            }

            Matcher kazakh = KAZAKH_NUMBERED.matcher(line);
            if (kazakh.matches()) {
                return Optional.of(
                        new HeadingMatch(
                                legalLevel(kazakh.group(1)),
                                line
                        )
                );
            }

            Matcher chinese = CHINESE_ORDINAL.matcher(line);
            if (chinese.matches()) {
                return Optional.of(
                        new HeadingMatch(
                                legalLevel(chinese.group(1)),
                                line
                        )
                );
            }

            if (NUMBERED_LINE.matcher(line).matches()) {
                return Optional.of(
                        new HeadingMatch(numberedLevel(line), line)
                );
            }

            return Optional.empty();
        }

        private int legalLevel(String token) {
            String upper = token.toUpperCase(java.util.Locale.ROOT);
            return switch (upper) {
                case "LAW", "ЗАКОН", "ЗАҢ", "法律", "GESETZ", "LOI",
                        "LEY", "LEI", "LEGGE", "KANUN", "ΝΟΜΟΣ" -> 1;
                case "PART", "ЧАСТЬ", "БӨЛІМ", "编", "TEIL",
                        "PARTIE", "PARTE", "KISIM", "ΜΕΡΟΣ" -> 2;
                case "CHAPTER", "ГЛАВА", "ТАРАУ", "章", "KAPITEL",
                        "CHAPITRE", "CAPÍTULO", "CAPITULO", "CAPITOLO",
                        "CAPO", "BÖLÜM", "ΚΕΦΑΛΑΙΟ" -> 3;
                case "SECTION", "РАЗДЕЛ", "БӨЛІК", "节", "ABSCHNITT",
                        "SECCIÓN", "SECCION", "SEÇÃO", "SECAO", "SECÇÃO",
                        "SECCAO", "SEZIONE", "ΤΜΗΜΑ" -> 4;
                case "ARTICLE", "СТАТЬЯ", "БАП", "条", "ARTIKEL",
                        "ARTÍCULO", "ARTICULO", "ARTIGO", "ARTICOLO",
                        "MADDE", "ΆΡΘΡΟ", "ΑΡΘΡΟ" -> 5;
                case "PARAGRAPH", "ПАРАГРАФ", "ТАРМАҚ", "款",
                        "ABSATZ", "PARAGRAPHE", "PÁRRAFO", "PARRAFO",
                        "PARÁGRAFO", "PARAGRAFO", "COMMA", "FIKRA",
                        "ΠΑΡΑΓΡΑΦΟΣ" -> 6;
                case "SUBPARAGRAPH", "ПОДПАРАГРАФ", "ТАРМАҚША", "项",
                        "UNTERABSATZ", "ALINÉA", "ALINEA", "APARTADO",
                        "INCISO", "ALÍNEA", "BENT", "ΕΔΆΦΙΟ",
                        "ΕΔΑΦΙΟ" -> 7;
                default -> throw new IllegalArgumentException(
                        "Unsupported legal heading token: " + token
                );
            };
        }

        private int numberedLevel(String value) {
            String token = value.split("\\s+", 2)[0]
                    .replaceAll("[.)]+$", "");
            return Math.min(
                    7,
                    5 + Math.max(
                            0,
                            token.split("\\.").length - 1
                    )
            );
        }
    }
}
