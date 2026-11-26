package com.epfisztner.knowledgeassistant.provider;

import com.epfisztner.knowledgeassistant.generation.ChatModelPort;
import com.epfisztner.knowledgeassistant.generation.ChatProvider;
import com.epfisztner.knowledgeassistant.generation.GroundedPrompt;
import com.epfisztner.knowledgeassistant.generation.GroundedPrompt.PresentedPassage;
import com.epfisztner.knowledgeassistant.rewrite.RewritePrompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic, extractive chat model that needs no API key: picks the sentences of the top
 * presented Passages that share the most terms with the Question and cites each with its
 * Passage's marker (each line of a list counts as a sentence). When the Question names Bank
 * Products, only the Passages of the most specific one named are used. Sentences are copied verbatim, so every figure in the Answer appears in the
 * cited Passage and the output always passes verification.
 * <p>
 * Term matching is accent-insensitive and uses a crude shared-prefix stem (Hungarian is heavily
 * suffixed: "díja" ~ "díj", "számla" ~ "számlavezetési").
 */
@Component
@ConditionalOnProperty(name = "knowledge.chat.provider", havingValue = "offline", matchIfMissing = true)
class OfflineChatModel implements ChatModelPort {

    static final ChatProvider PROVIDER = ChatProvider.OFFLINE;
    static final String MODEL = "extractive-v1";
    /**
     * The offline rewrite is the deterministic join, not the extractive answer model.
     */
    static final String REWRITE_MODEL = "join-v1";

    /**
     * Sentence ends and line breaks: list items have no punctuation, and every Answer line needs its own marker.
     */
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+|\\s*\\R\\s*");
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");
    private static final int MIN_TERM = 3;
    private static final int STEM = 5;

    private final int maxPassages;
    private final int maxSentences;

    OfflineChatModel(@Value("${knowledge.chat.offline.max-passages:3}") int maxPassages,
                     @Value("${knowledge.chat.offline.max-sentences:3}") int maxSentences) {
        this.maxPassages = maxPassages;
        this.maxSentences = maxSentences;
    }

    @Override
    public ChatProvider provider() {
        return PROVIDER;
    }

    @Override
    public String model() {
        return MODEL;
    }

    private record Candidate(int marker, int index, String sentence, int score) {
    }

    @Override
    public ChatReply generate(GroundedPrompt prompt) {
        Set<String> questionTerms = terms(prompt.question());
        // Bank Product disambiguation: when the Question names Bank Products ("CIB ECO" vs "CIB ECO Plusz"),
        // answer only from the Passages of the most specific one it names.
        int specificity = prompt.passages().stream().mapToInt(p -> namedProductTerms(questionTerms, p)).max().orElse(0);
        List<PresentedPassage> top = prompt.passages().stream()
                .filter(p -> namedProductTerms(questionTerms, p) >= specificity)
                .limit(maxPassages).toList();
        // The named Bank Product counts as one term, however often or completely a sentence repeats its name;
        // otherwise disclaimers listing product names ("CIB ECO, CIB ECO Plusz bankszámla...") outrank the facts.
        Set<String> productTerms = new LinkedHashSet<>();
        if (specificity > 0) {
            top.forEach(p -> productTerms.addAll(terms(p.passage().source().bankProduct())));
        }
        Set<String> otherTerms = new LinkedHashSet<>(questionTerms);
        otherTerms.removeIf(q -> productTerms.stream().anyMatch(t -> sameStem(q, t)));
        List<Candidate> candidates = new ArrayList<>();
        for (PresentedPassage presented : top) {
            String[] sentences = SENTENCE_END.split(presented.passage().text().strip());
            for (int i = 0; i < sentences.length; i++) {
                String sentence = sentences[i].strip();
                if (!sentence.isEmpty()) {
                    Set<String> sentenceTerms = terms(sentence);
                    int productMention = score(productTerms, sentenceTerms) > 0 ? 1 : 0;
                    candidates.add(new Candidate(presented.marker(), i, sentence,
                            score(otherTerms, sentenceTerms) + productMention));
                }
            }
        }
        if (candidates.isEmpty()) {
            return new ChatReply("", PROVIDER, MODEL);
        }
        int best = candidates.stream().mapToInt(Candidate::score).max().orElse(0);
        List<Candidate> chosen = best == 0
                ? List.of(candidates.getFirst())
                : candidates.stream()
                .filter(c -> c.score() * 4 >= best * 3)
                .sorted(Comparator.comparingInt(Candidate::score).reversed()
                        .thenComparingInt(Candidate::marker).thenComparingInt(Candidate::index))
                .limit(maxSentences)
                .toList();
        String answer = String.join("\n\n", chosen.stream().map(c -> c.sentence() + " [" + c.marker() + "]").toList());
        return new ChatReply(answer, PROVIDER, MODEL);
    }

    /**
     * The previous Standalone Question joined with the new Question (no model involved).
     */
    @Override
    public ChatReply rewrite(RewritePrompt prompt) {
        return new ChatReply(prompt.joinedWithPrevious(), PROVIDER, REWRITE_MODEL);
    }

    /**
     * The number of terms of the Passage's Bank Product if the Question names all of them, else 0.
     */
    private static int namedProductTerms(Set<String> questionTerms, PresentedPassage presented) {
        String bankProduct = presented.passage().source().bankProduct();
        if (bankProduct == null) {
            return 0;
        }
        Set<String> productTerms = terms(bankProduct);
        return !productTerms.isEmpty() && score(productTerms, questionTerms) == productTerms.size() ? productTerms.size() : 0;
    }

    /**
     * How many distinct Question terms the sentence matches.
     */
    private static int score(Set<String> questionTerms, Set<String> sentenceTerms) {
        return (int) questionTerms.stream()
                .filter(q -> sentenceTerms.stream().anyMatch(s -> sameStem(q, s)))
                .count();
    }

    private static boolean sameStem(String a, String b) {
        int k = Math.min(STEM, Math.min(a.length(), b.length()));
        return k >= MIN_TERM && a.regionMatches(0, b, 0, k);
    }

    private static Set<String> terms(String text) {
        String folded = DIACRITICS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("")
                .toLowerCase(Locale.ROOT);
        Set<String> terms = new LinkedHashSet<>();
        for (String token : NON_WORD.split(folded)) {
            if (token.length() >= MIN_TERM) {
                terms.add(token);
            }
        }
        return terms;
    }
}
