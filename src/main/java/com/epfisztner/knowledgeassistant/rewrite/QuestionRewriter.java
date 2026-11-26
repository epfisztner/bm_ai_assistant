package com.epfisztner.knowledgeassistant.rewrite;

import com.epfisztner.knowledgeassistant.generation.ChatModelPort.ChatReply;

import java.util.List;

/**
 * Produces the Standalone Question from the new Question and the Conversation's earlier Turns
 * (ADR-0003: history is used only here, never as evidence).
 */
public interface QuestionRewriter {

    Rewrite rewrite(String question, List<PriorTurn> history);

    /**
     * An earlier Turn of the Conversation, oldest first; {@code answerText} is null for none.
     */
    record PriorTurn(String question, String standaloneQuestion, String answerText) {
    }

    /**
     * The Standalone Question and, when the chat model was asked (follow-ups only), how: the
     * rewrite prompt version, the model's raw reply with its provider/model, and whether the
     * reply was blank so the previous Standalone Question was joined with the Question instead.
     * {@code promptVersion} and {@code reply} are null for a first Turn.
     */
    record Rewrite(String standaloneQuestion, String promptVersion, ChatReply reply, boolean fellBackToJoin) {

        /**
         * A Question that needs no rewriting is its own Standalone Question.
         */
        public static Rewrite asIs(String question) {
            return new Rewrite(question, null, null, false);
        }

        public boolean modelAsked() {
            return reply != null;
        }
    }
}
