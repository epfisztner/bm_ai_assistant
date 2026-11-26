package com.epfisztner.knowledgeassistant.generation;

import com.epfisztner.knowledgeassistant.rewrite.RewritePrompt;

/**
 * The chat model behind a provider port. Adapters live in the {@code provider} package and are
 * selected with {@code knowledge.chat.provider} (see {@link ChatProvider}).
 * <p>
 * Real adapters send the prompts' system and user text; the deterministic offline adapter works
 * from their structured parts. Every reply names the provider and model that actually produced
 * it, for the Turn and its audit record.
 */
public interface ChatModelPort {

    /**
     * The configured chat provider, recorded on each Turn.
     */
    ChatProvider provider();

    /**
     * The configured model name, recorded on each Turn.
     */
    String model();

    ChatReply generate(GroundedPrompt prompt);

    /**
     * Rewrites a follow-up into a Standalone Question with the versioned rewrite prompt.
     */
    ChatReply rewrite(RewritePrompt prompt);

    /**
     * The model's raw output plus which provider/model produced it (for the audit record).
     */
    record ChatReply(String text, ChatProvider provider, String model) {
    }
}
