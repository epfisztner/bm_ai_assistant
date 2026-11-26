package com.epfisztner.knowledgeassistant.verification;

import com.epfisztner.knowledgeassistant.generation.GroundedPrompt;
import com.epfisztner.knowledgeassistant.generation.GroundedPrompt.PresentedPassage;
import com.epfisztner.knowledgeassistant.retrieval.RetrievedPassage;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The deterministic release check of ADR-0002, applied to every paragraph and every list item
 * of the model output:
 * <ol>
 *   <li>it carries at least one Citation marker;</li>
 *   <li>every marker refers to a Passage presented in this Turn;</li>
 *   <li>every numeric fact (digits with optional thousand separators and decimal comma, an
 *       optional Hungarian scale word such as ezer, millió, milliárd, and an optional unit such
 *       as Ft, %, év, hónap, nap) appears, after whitespace, separator and scale normalization,
 *       in a Passage that unit cites. When the output gives a unit, the Passage must give the
 *       same unit (so "84 nap" is not backed by "84 hónap", and "225 ezer év" is not backed by
 *       "225 ezer Ft").</li>
 * </ol>
 * There are no exemptions: a heading or a lead-in line before a list is a paragraph too and needs
 * its own Citation. Deliberately strict: false Refusals are preferred over unverified output.
 */
@Component
class GroundingVerifier implements AnswerVerifier {

    private static final Pattern BLANK_LINES = Pattern.compile("\\R\\s*\\R");
    private static final Pattern LIST_ITEM = Pattern.compile("^\\s*(?:[-*•–]|\\d{1,2}[.)])\\s+");

    /**
     * A number: digits with optional thousand groups (space, no-break space or dot) and decimal
     * comma, an optional Hungarian scale word, and an optional unit word (matched by its stem,
     * so Hungarian suffixes are allowed).
     */
    private static final Pattern NUMERIC_FACT = Pattern.compile(
            "(?<![\\p{L}\\d])(\\d{1,3}(?:[ \\u00A0\\u202F.]\\d{3})+(?!\\d)|\\d+)(?:,(\\d+))?"
                    + "(?:\\h*(milliárd|millió|ezer)\\p{L}*)?"
                    + "(?:\\h*(%|(?:Ft|HUF)(?!\\p{L})|(?:forint|százalék|hónap|nap|év)\\p{L}*))?",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /**
     * A figure normalized for comparison; {@code unit} is null when none was written.
     */
    record Figure(String number, String unit, String asWritten) {

        boolean backedBy(Figure passageFigure) {
            return number.equals(passageFigure.number) && (unit == null || unit.equals(passageFigure.unit));
        }
    }

    /**
     * One paragraph or list item with the Citation markers it carries.
     */
    private record Unit(int number, String text, Set<Integer> markers) {
    }

    @Override
    public Verdict verify(String modelOutput, GroundedPrompt prompt) {
        List<Unit> units = units(modelOutput == null ? "" : modelOutput);
        if (units.isEmpty()) {
            return Verdict.fail(List.of("the Answer is empty"));
        }
        List<String> failed = new ArrayList<>();
        for (Unit unit : units) {
            check(unit, prompt, failed);
        }
        return failed.isEmpty() ? Verdict.pass() : Verdict.fail(failed);
    }

    private static void check(Unit unit, GroundedPrompt prompt, List<String> failed) {
        String where = "paragraph " + unit.number() + " (\"" + abbreviate(unit.text()) + "\")";
        if (unit.markers().isEmpty()) {
            failed.add(where + ": no Citation marker");
            return;
        }
        List<RetrievedPassage> cited = new ArrayList<>();
        for (int marker : unit.markers()) {
            Optional<PresentedPassage> passage = prompt.passage(marker);
            if (passage.isEmpty()) {
                failed.add(where + ": marker [" + marker + "] does not refer to a Passage presented in this Turn");
            } else {
                cited.add(passage.get().passage());
            }
        }
        List<Figure> backing = cited.stream().flatMap(GroundingVerifier::passageFigures).toList();
        for (Figure figure : figures(CitationMarkers.strip(unit.text()))) {
            if (backing.stream().noneMatch(figure::backedBy)) {
                failed.add(where + ": figure \"" + figure.asWritten() + "\" does not appear in the cited Passages "
                        + unit.markers());
            }
        }
    }

    /**
     * Splits the output into paragraphs (blank-line separated) and list items.
     */
    private static List<Unit> units(String output) {
        List<Unit> units = new ArrayList<>();
        for (String block : BLANK_LINES.split(output.strip())) {
            if (block.isBlank()) {
                continue;
            }
            List<StringBuilder> items = new ArrayList<>();
            StringBuilder leadIn = new StringBuilder();
            for (String line : block.strip().split("\\R")) {
                if (LIST_ITEM.matcher(line).find()) {
                    items.add(new StringBuilder(LIST_ITEM.matcher(line).replaceFirst("")));
                } else if (items.isEmpty()) {
                    leadIn.append(leadIn.isEmpty() ? "" : "\n").append(line.strip());
                } else {
                    items.getLast().append('\n').append(line.strip());
                }
            }
            if (!leadIn.isEmpty()) {
                units.add(unit(units.size() + 1, leadIn.toString()));
            }
            for (StringBuilder item : items) {
                units.add(unit(units.size() + 1, item.toString()));
            }
        }
        return units;
    }

    private static Unit unit(int number, String text) {
        return new Unit(number, text, new LinkedHashSet<>(CitationMarkers.in(text)));
    }

    private static Stream<Figure> passageFigures(RetrievedPassage passage) {
        String text = String.join("\n", Stream.concat(
                Stream.of(passage.source().sourceTitle(), passage.source().bankProduct()), passage.headingPath().stream()).toList())
                + "\n" + passage.text();
        return figures(text).stream();
    }

    static List<Figure> figures(String text) {
        List<Figure> figures = new ArrayList<>();
        Matcher m = NUMERIC_FACT.matcher(text);
        while (m.find()) {
            String number = normalizeNumber(m.group(1), m.group(2), m.group(3));
            figures.add(new Figure(number, unit(m.group(4)), m.group().strip()));
        }
        return figures;
    }

    private static String normalizeNumber(String integerPart, String fractionalPart, String scaleWord) {
        String raw = integerPart.replaceAll("[^0-9]", "")
                + (fractionalPart == null ? "" : "." + fractionalPart);
        BigDecimal value = new BigDecimal(raw).multiply(multiplier(scaleWord));
        return value.stripTrailingZeros().toPlainString();
    }

    private static BigDecimal multiplier(String scaleWord) {
        if (scaleWord == null) {
            return BigDecimal.ONE;
        }
        return switch (scaleWord.toLowerCase(Locale.ROOT)) {
            case "ezer" -> BigDecimal.valueOf(1_000L);
            case "millió" -> BigDecimal.valueOf(1_000_000L);
            case "milliárd" -> BigDecimal.valueOf(1_000_000_000L);
            default -> BigDecimal.ONE;
        };
    }

    private static String unit(String written) {
        if (written == null) {
            return null;
        }
        String unit = written.toLowerCase(Locale.ROOT);
        if (unit.equals("%") || unit.startsWith("százalék")) {
            return "%";
        }
        if (unit.equals("ft") || unit.equals("huf") || unit.startsWith("forint")) {
            return "Ft";
        }
        for (String stem : List.of("hónap", "nap", "év")) {
            if (unit.startsWith(stem)) {
                return stem;
            }
        }
        return unit;
    }

    private static String abbreviate(String text) {
        String oneLine = text.replaceAll("\\s+", " ").strip();
        return oneLine.length() <= 60 ? oneLine : oneLine.substring(0, 57) + "...";
    }
}
