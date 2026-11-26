package com.epfisztner.knowledgeassistant.ingestion;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Turns a fetched HTML page into a title, its extracted text and an ordered list of
 * heading-labeled Passages.
 * <p>
 * The page's main content element ({@code <main>}, {@code [role=main]}, {@code <article>}) is
 * preferred; otherwise the body is used with navigation, header and footer removed. Scripts,
 * styles, forms, menus, asides (related content), ad zones and mobile-only duplicates are always
 * removed. Passages are split at headings; footnotes get their own Passage, and a section longer
 * than {@code knowledge.extraction.max-passage-chars} is split at paragraph boundaries.
 */
@Component
class ContentExtractor {

    record ExtractedPassage(int ordinal, List<String> headingPath, String text) {
    }

    record ExtractedPage(String title, String text, List<ExtractedPassage> passages) {
    }

    private static final String ALWAYS_REMOVED = "script, style, noscript, template, form, iframe, svg, button, select, input, textarea, "
            + "[role=menu], [role=menubar], aside, .adzone, :has(> .goAdverticum), "
            // Bootstrap mobile-only variants duplicate a desktop variant of the same content.
            + ".d-md-none, .d-md-hide";
    private static final String BOILERPLATE = "nav, header, footer, [role=navigation], [role=banner], [role=contentinfo]";
    private static final Set<String> HEADINGS = Set.of("h1", "h2", "h3", "h4", "h5", "h6");
    private static final Set<String> INLINE = Set.of("a", "abbr", "b", "bdi", "bdo", "cite", "code", "data", "dfn", "em",
            "i", "kbd", "mark", "q", "s", "samp", "small", "span", "strong", "sub", "sup", "time", "u", "var", "wbr", "label");

    private final int maxPassageChars;

    ContentExtractor(@Value("${knowledge.extraction.max-passage-chars:1500}") int maxPassageChars) {
        this.maxPassageChars = maxPassageChars;
    }

    ExtractedPage extract(String html, String baseUrl) {
        Document document = Jsoup.parse(html, baseUrl);
        String title = titleOf(document);
        placeStreamedContent(document);

        Element root = document.selectFirst("main, [role=main]");
        if (root == null) {
            root = document.selectFirst("article");
        }
        if (root == null) {
            root = document.body();
            root.select(BOILERPLATE).remove();
        }
        root.select(ALWAYS_REMOVED).remove();

        var blocks = new BlockCollector();
        blocks.walk(root);
        blocks.flush();

        String text = blocks.blocks.stream().map(Block::text).collect(Collectors.joining("\n\n"));
        return new ExtractedPage(title, text, toPassages(blocks.blocks));
    }

    /**
     * Server-streamed (React Suspense) pages render a placeholder {@code <template id="B:n">} where
     * content belongs and send the content later in a hidden {@code <div id="S:n">} at the end of the
     * body; a browser script swaps them. Do the same swap so the content sits under its heading.
     */
    private static void placeStreamedContent(Document document) {
        for (Element placeholder : document.select("template[id^=B:]")) {
            Element streamed = document.getElementById("S:" + placeholder.id().substring(2));
            if (streamed != null && streamed.hasAttr("hidden")) {
                streamed.remove();
                placeholder.after(streamed.removeAttr("hidden"));
                placeholder.remove();
            }
        }
    }

    private static String titleOf(Document document) {
        String title = clean(document.title());
        if (title.isEmpty()) {
            Element h1 = document.selectFirst("h1");
            title = h1 == null ? "" : clean(h1.text());
        }
        return title;
    }

    // --- block collection -------------------------------------------------------------------

    /**
     * A heading (level 1-6) or a paragraph (level 0) in document order.
     */
    private record Block(int level, String text) {
        boolean isHeading() {
            return level > 0;
        }
    }

    private static final class BlockCollector {
        final List<Block> blocks = new ArrayList<>();
        private final StringBuilder inline = new StringBuilder();

        void walk(Element element) {
            for (Node child : element.childNodes()) {
                if (child instanceof TextNode textNode) {
                    inline.append(textNode.getWholeText());
                } else if (child instanceof Element el) {
                    String tag = el.normalName();
                    if (HEADINGS.contains(tag)) {
                        flush();
                        add(tag.charAt(1) - '0', el.text());
                    } else if ("br".equals(tag)) {
                        inline.append(' ');
                    } else if (INLINE.contains(tag) && !containsBlock(el)) {
                        inline.append(el.text());
                    } else if ("tr".equals(tag)) {
                        flush();
                        add(0, el.children().stream().map(Element::text).map(ContentExtractor::clean)
                                .filter(cell -> !cell.isEmpty()).collect(Collectors.joining(" | ")));
                    } else if (!containsBlock(el)) {
                        flush();
                        add(0, leafText(el));
                    } else {
                        flush();
                        walk(el);
                        flush();
                    }
                }
            }
        }

        void flush() {
            add(0, inline.toString());
            inline.setLength(0);
        }

        private void add(int level, String raw) {
            String text = clean(raw);
            if (!text.isEmpty()) {
                blocks.add(new Block(level, text));
            }
        }

        /**
         * The text of an element holding only inline markup. Sibling inline elements with no text
         * between them (e.g. a label span and a value span laid out side by side) are separated by a
         * space, so "Ingyenesség hossza" and "2 év" do not run together.
         */
        private static String leafText(Element el) {
            boolean onlyElements = el.childNodes().stream()
                    .noneMatch(node -> node instanceof TextNode text && !text.isBlank());
            if (!onlyElements || el.childrenSize() < 2) {
                return el.text();
            }
            return el.children().stream().map(Element::text).collect(Collectors.joining(" "));
        }

        /**
         * True if {@code el} has a descendant that is not inline text markup.
         */
        private static boolean containsBlock(Element el) {
            for (Element descendant : el.getAllElements()) {
                if (descendant != el && !INLINE.contains(descendant.normalName()) && !"br".equals(descendant.normalName())) {
                    return true;
                }
            }
            return false;
        }
    }

    // --- passage splitting ------------------------------------------------------------------

    private List<ExtractedPassage> toPassages(List<Block> blocks) {
        var passages = new ArrayList<ExtractedPassage>();
        Deque<Block> headingStack = new ArrayDeque<>();
        var paragraphs = new ArrayList<String>();

        for (Block block : blocks) {
            if (block.isHeading()) {
                emitSection(headingPath(headingStack), paragraphs, passages);
                paragraphs.clear();
                while (!headingStack.isEmpty() && headingStack.peekLast().level() >= block.level()) {
                    headingStack.removeLast();
                }
                headingStack.addLast(block);
            } else {
                paragraphs.add(block.text());
            }
        }
        emitSection(headingPath(headingStack), paragraphs, passages);
        return passages;
    }

    private static List<String> headingPath(Deque<Block> headingStack) {
        return headingStack.stream().map(Block::text).toList();
    }

    /**
     * Packs a section's paragraphs into Passages of at most {@code maxPassageChars} (a single longer
     * paragraph stays whole). Footnotes (paragraphs starting with {@code *}: the conditions and
     * disclaimers attached to starred figures) never share a Passage with body text.
     */
    private void emitSection(List<String> headingPath, List<String> paragraphs, List<ExtractedPassage> passages) {
        var current = new StringBuilder();
        boolean currentIsFootnotes = false;
        for (String paragraph : paragraphs) {
            boolean footnote = paragraph.startsWith("*");
            if (!current.isEmpty() && (footnote != currentIsFootnotes
                    || current.length() + 2 + paragraph.length() > maxPassageChars)) {
                passages.add(new ExtractedPassage(passages.size() + 1, headingPath, current.toString()));
                current.setLength(0);
            }
            if (!current.isEmpty()) {
                current.append("\n\n");
            }
            current.append(paragraph);
            currentIsFootnotes = footnote;
        }
        if (!current.isEmpty()) {
            passages.add(new ExtractedPassage(passages.size() + 1, headingPath, current.toString()));
        }
    }

    /**
     * Collapses whitespace and drops invisible format characters (e.g. the zero-width joiners of loading placeholders).
     */
    private static String clean(String text) {
        return text.replace(' ', ' ').replaceAll("\\p{Cf}", "").replaceAll("\\s+", " ").strip();
    }
}
