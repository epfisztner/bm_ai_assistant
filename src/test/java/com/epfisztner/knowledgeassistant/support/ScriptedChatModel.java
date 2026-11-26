package com.epfisztner.knowledgeassistant.support;

import com.epfisztner.knowledgeassistant.generation.ChatModelPort;
import com.epfisztner.knowledgeassistant.generation.ChatProvider;
import com.epfisztner.knowledgeassistant.generation.GroundedPrompt;
import com.epfisztner.knowledgeassistant.rewrite.RewritePrompt;
import org.springframework.beans.factory.ObjectProvider;

import java.util.function.Function;

/**
 * Test-only chat model that lets a test dictate the model output for its next Turn.
 * <p>
 * Registered as the {@code @Primary} {@link ChatModelPort} for all integration tests (see
 * {@link IntegrationTest}). Unscripted, it delegates to the configured provider adapter (the
 * offline one in tests), so other tests are unaffected. A script is used once, then cleared;
 * {@link IntegrationTest} also clears it before every test. Scripted replies report the
 * configured provider with model {@value #MODEL}.
 */
public class ScriptedChatModel implements ChatModelPort {

    public static final String MODEL = "scripted";

    private final ObjectProvider<ChatModelPort> adapters;
    private volatile Function<GroundedPrompt, String> script;
    private volatile String rewriteScript;

    ScriptedChatModel(ObjectProvider<ChatModelPort> adapters) {
        this.adapters = adapters;
    }

    /** The next Turn's model output is exactly {@code output}. */
    public void replyWith(String output) {
        replyWith(prompt -> output);
    }

    /** The next Turn's model output is computed from the prompt (e.g. to pick a Passage's marker). */
    public void replyWith(Function<GroundedPrompt, String> script) {
        this.script = script;
    }

    /** The model's raw reply to the next question-rewrite request is exactly {@code reply}. */
    public void rewriteWith(String reply) {
        this.rewriteScript = reply;
    }

    public void reset() {
        script = null;
        rewriteScript = null;
    }

    @Override
    public ChatProvider provider() {
        return adapter().provider();
    }

    @Override
    public String model() {
        return adapter().model();
    }

    @Override
    public ChatReply generate(GroundedPrompt prompt) {
        Function<GroundedPrompt, String> current = script;
        if (current == null) {
            return adapter().generate(prompt);
        }
        script = null;
        return new ChatReply(current.apply(prompt), provider(), MODEL);
    }

    @Override
    public ChatReply rewrite(RewritePrompt prompt) {
        String current = rewriteScript;
        if (current == null) {
            return adapter().rewrite(prompt);
        }
        rewriteScript = null;
        return new ChatReply(current, provider(), MODEL);
    }

    private ChatModelPort adapter() {
        return adapters.orderedStream().filter(adapter -> adapter != this).findFirst().orElseThrow();
    }
}
