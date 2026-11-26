package com.epfisztner.knowledgeassistant.rewrite;

import com.epfisztner.knowledgeassistant.rewrite.QuestionRewriter.PriorTurn;

import java.util.List;

/**
 * The prompt asking the chat model to turn a follow-up into a Standalone Question.
 * <p>
 * Real adapters send {@link #system()} and {@link #user()}; the offline adapter works from the
 * structured {@link #question()} and {@link #history()} (see {@link #joinedWithPrevious()}).
 *
 * @param history the earlier Turns in the rewrite window, oldest first (never empty)
 */
public record RewritePrompt(String promptVersion, String system, String user, String question,
                            List<PriorTurn> history) {

    /**
     * The deterministic rewrite used without a model: the previous Standalone Question joined
     * with the new Question.
     */
    public String joinedWithPrevious() {
        PriorTurn previous = history.getLast();
        String context = previous.standaloneQuestion() != null ? previous.standaloneQuestion() : previous.question();
        return context.strip() + " " + question.strip();
    }
}
