package kz.alimbetov.akmai.knowledge.chunking;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDocument;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import kz.alimbetov.akmai.knowledge.model.SemanticUnit;
import kz.alimbetov.akmai.knowledge.model.SemanticUnitType;
import kz.alimbetov.akmai.knowledge.model.StructuralRole;
import org.springframework.stereotype.Component;

@Component
public class StructuralUnitExtractor {

    private static final Pattern MARKDOWN_HEADING =
            Pattern.compile("^(#{1,6})\\s+(.+)$");

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

    private static final Pattern KAZAKH_NUMBERED_LEGAL_HEADING =
            Pattern.compile(
                    "^\\d+(?:\\.\\d+)*[-‑–—]?\\s*"
                            + "(БӨЛІМ|ТАРАУ|БӨЛІК|БАП|ТАРМАҚША|ТАРМАҚ)"
                            + "(?=\\s|$|[.:：]).*$",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
            );

    private static final Pattern CHINESE_ORDINAL_LEGAL_HEADING =
            Pattern.compile(
                    "^第[一二三四五六七八九十百千万零〇两\\d]+"
                            + "(编|章|节|条|款|项).*$"
            );

    private static final Pattern NUMBERED_LINE =
            Pattern.compile("^\\d+(?:\\.\\d+)*[.)]?\\s+.+$");

    public List<SemanticUnit> extract(
            KnowledgeDocument document,
            String normalizedText
    ) {
        List<SemanticUnit> units = new ArrayList<>();
        Deque<Heading> hierarchy = new ArrayDeque<>();
        String currentSection = document.title();

        if (document.domain() == KnowledgeDomain.LEGAL
                && document.title() != null
                && !document.title().isBlank()) {
            hierarchy.addLast(new Heading(1, document.title()));
        }

        for (String block : normalizedText.split("\\n\\s*\\n")) {
            String value = block.trim();
            if (value.isEmpty()) {
                continue;
            }

            Heading heading = heading(value, document.domain());
            if (heading != null && value.length() < 220) {
                while (!hierarchy.isEmpty()
                        && hierarchy.peekLast().level() >= heading.level()) {
                    hierarchy.removeLast();
                }
                hierarchy.addLast(heading);
                currentSection = hierarchy.stream()
                        .map(Heading::text)
                        .collect(Collectors.joining(" > "));
                units.add(new SemanticUnit(
                        value,
                        currentSection,
                        SemanticUnitType.HEADING,
                        true,
                        StructuralRole.HEADING
                ));
                continue;
            }

            for (String paragraph : MultilingualSentenceSplitter.split(
                    value,
                    document.language()
            )) {
                String text = paragraph.trim();
                if (!text.isEmpty()) {
                    StructuralRole role =
                            NUMBERED_LINE.matcher(text).matches()
                                    ? StructuralRole.LIST_ITEM
                                    : StructuralRole.PARAGRAPH;
                    units.add(new SemanticUnit(
                            text,
                            currentSection,
                            SemanticUnitType.PARAGRAPH,
                            false,
                            role
                    ));
                }
            }
        }

        return units;
    }

    private Heading heading(
            String value,
            KnowledgeDomain domain
    ) {
        Matcher markdown = MARKDOWN_HEADING.matcher(value);
        if (markdown.matches()) {
            return new Heading(markdown.group(1).length(), value);
        }

        Matcher legal = LEGAL_HEADING.matcher(value);
        if (legal.matches()) {
            return new Heading(legalLevel(legal.group(1)), value);
        }

        Matcher kazakh =
                KAZAKH_NUMBERED_LEGAL_HEADING.matcher(value);
        if (kazakh.matches()) {
            return new Heading(legalLevel(kazakh.group(1)), value);
        }

        Matcher chinese =
                CHINESE_ORDINAL_LEGAL_HEADING.matcher(value);
        if (chinese.matches()) {
            return new Heading(legalLevel(chinese.group(1)), value);
        }

        if (domain == KnowledgeDomain.LEGAL
                && NUMBERED_LINE.matcher(value).matches()) {
            return new Heading(numberedLevel(value), value);
        }

        return null;
    }

    private int legalLevel(String token) {
        String upper = token.toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "LAW", "ЗАКОН", "ЗАҢ", "法律", "GESETZ", "LOI",
                    "LEY", "LEI", "LEGGE", "KANUN", "ΝΟΜΟΣ" -> 1;

            case "PART", "ЧАСТЬ", "БӨЛІМ", "编", "TEIL", "PARTIE",
                    "PARTE", "KISIM", "ΜΕΡΟΣ" -> 2;

            case "CHAPTER", "ГЛАВА", "ТАРАУ", "章", "KAPITEL",
                    "CHAPITRE", "CAPÍTULO", "CAPITULO", "CAPITOLO",
                    "CAPO", "BÖLÜM", "ΚΕΦΑΛΑΙΟ" -> 3;

            case "SECTION", "РАЗДЕЛ", "БӨЛІК", "节", "ABSCHNITT",
                    "SECCIÓN", "SECCION", "SEÇÃO", "SECAO", "SECÇÃO",
                    "SECCAO", "SEZIONE", "ΤΜΗΜΑ" -> 4;

            case "ARTICLE", "СТАТЬЯ", "БАП", "条", "ARTIKEL",
                    "ARTÍCULO", "ARTICULO", "ARTIGO", "ARTICOLO",
                    "MADDE", "ΆΡΘΡΟ", "ΑΡΘΡΟ" -> 5;

            case "PARAGRAPH", "ПАРАГРАФ", "ТАРМАҚ", "款", "ABSATZ",
                    "PARAGRAPHE", "PÁRRAFO", "PARRAFO", "PARÁGRAFO",
                    "PARAGRAFO", "COMMA", "FIKRA", "ΠΑΡΑΓΡΑΦΟΣ" -> 6;

            case "SUBPARAGRAPH", "ПОДПАРАГРАФ", "ТАРМАҚША", "项",
                    "UNTERABSATZ", "ALINÉA", "ALINEA", "APARTADO",
                    "INCISO", "ALÍNEA", "BENT", "ΕΔΆΦΙΟ",
                    "ΕΔΑΦΙΟ" -> 7;

            default -> 7;
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

    private record Heading(
            int level,
            String text
    ) {
    }
}
