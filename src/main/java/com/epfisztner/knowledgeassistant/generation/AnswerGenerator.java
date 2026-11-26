package com.epfisztner.knowledgeassistant.generation;

import com.epfisztner.knowledgeassistant.generation.ChatModelPort.ChatReply;
import com.epfisztner.knowledgeassistant.retrieval.RetrievedPassage;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Builds the grounded prompt from the retrieved Passages and calls the chat model.
 */
@Component
public class AnswerGenerator {

    /**
     * The prompt that was sent and the model's raw reply.
     */
    public record Generation(GroundedPrompt prompt, ChatReply reply) {

        /**
         * The model replied with the decline sentinel: the Passages are insufficient.
         */
        public boolean declined() {
            return reply.text() != null && reply.text().contains(AnswerPrompt.DECLINE_SENTINEL);
        }
    }

    private final ChatModelPort chatModel;

    AnswerGenerator(ChatModelPort chatModel) {
        this.chatModel = chatModel;
    }

    public Generation generate(String standaloneQuestion, List<RetrievedPassage> passages) {
        GroundedPrompt prompt = AnswerPrompt.build(standaloneQuestion, passages);
        return new Generation(prompt, chatModel.generate(prompt));
    }
}
