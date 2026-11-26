package com.epfisztner.knowledgeassistant.rewrite;

import com.epfisztner.knowledgeassistant.rewrite.QuestionRewriter.PriorTurn;

import java.util.List;

/**
 * The versioned Hungarian rewrite prompt. Change {@link #VERSION} whenever the wording changes,
 * so audit records show which prompt produced a Standalone Question.
 * <p>
 * Earlier Answers are shown only so that references like "és az Erste-nél?" can be resolved;
 * the model is told not to carry facts from them into the question (ADR-0003).
 */
final class QuestionRewritePrompt {

    static final String VERSION = "rewrite-v1";

    private static final String SYSTEM = """
            Egy banki termékekről szóló beszélgetés következő kérdését kell önálló kérdéssé átfogalmaznod.
            Szabályok:
            - Az önálló kérdés a korábbi beszélgetés nélkül is érthető legyen: nevezd meg a bankot, a terméket és a kérdezett jellemzőt.
            - A korábbi kérdéseket és válaszokat csak arra használd, hogy kiderüljön, mire vonatkozik az új kérdés.
            - Ne válaszold meg a kérdést, és ne vegyél át tényeket, számokat a korábbi válaszokból.
            - Ha az új kérdés önmagában is érthető, add vissza változatlanul.
            - Csak az önálló kérdést írd le, egyetlen sorban, magyarázat nélkül.
            """;

    private QuestionRewritePrompt() {
    }

    static RewritePrompt build(String question, List<PriorTurn> history) {
        StringBuilder user = new StringBuilder("Korábbi beszélgetés:\n\n");
        for (PriorTurn turn : history) {
            user.append("Kérdés: ").append(turn.question()).append('\n');
            if (turn.answerText() != null) {
                user.append("Válasz: ").append(turn.answerText()).append('\n');
            }
            user.append('\n');
        }
        user.append("Új kérdés: ").append(question).append("\n\nÖnálló kérdés:");
        return new RewritePrompt(VERSION, SYSTEM, user.toString(), question, List.copyOf(history));
    }
}
