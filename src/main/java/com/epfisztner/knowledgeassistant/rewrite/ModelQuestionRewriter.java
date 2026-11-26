package com.epfisztner.knowledgeassistant.rewrite;

import com.epfisztner.knowledgeassistant.generation.ChatModelPort;
import com.epfisztner.knowledgeassistant.generation.ChatModelPort.ChatReply;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Rewrites a follow-up into a Standalone Question through the chat model port with the versioned
 * rewrite prompt. A first Turn (no history) is its own Standalone Question and the model is not
 * called; a blank model reply falls back to the deterministic join.
 */
@Component
class ModelQuestionRewriter implements QuestionRewriter {

    private final ChatModelPort chatModel;

    ModelQuestionRewriter(ChatModelPort chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public Rewrite rewrite(String question, List<PriorTurn> history) {
        if (history.isEmpty()) {
            return Rewrite.asIs(question);
        }
        RewritePrompt prompt = QuestionRewritePrompt.build(question, history);
        ChatReply reply = chatModel.rewrite(prompt);
        String standalone = reply.text() == null ? "" : reply.text().strip().lines().findFirst().orElse("").strip();
        return standalone.isEmpty()
                ? new Rewrite(prompt.joinedWithPrevious(), prompt.promptVersion(), reply, true)
                : new Rewrite(standalone, prompt.promptVersion(), reply, false);
    }
}
