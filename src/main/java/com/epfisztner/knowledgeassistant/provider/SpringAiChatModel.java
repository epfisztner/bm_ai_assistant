package com.epfisztner.knowledgeassistant.provider;

import com.epfisztner.knowledgeassistant.generation.ChatModelPort;
import com.epfisztner.knowledgeassistant.generation.ChatProvider;
import com.epfisztner.knowledgeassistant.generation.ChatProviderException;
import com.epfisztner.knowledgeassistant.generation.GroundedPrompt;
import com.epfisztner.knowledgeassistant.rewrite.RewritePrompt;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/**
 * A real chat provider behind the port: sends the versioned prompts' system and user text through
 * a Spring AI chat model client (OpenAI-compatible or Anthropic, see {@link ChatProviderConfiguration})
 * and reports which provider and model produced the reply.
 */
class SpringAiChatModel implements ChatModelPort {

    private final ChatProvider provider;
    private final String model;
    private final ChatModel client;

    SpringAiChatModel(ChatProvider provider, String model, ChatModel client) {
        this.provider = provider;
        this.model = model;
        this.client = client;
    }

    @Override
    public ChatProvider provider() {
        return provider;
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public ChatReply generate(GroundedPrompt prompt) {
        return call(prompt.system(), prompt.user());
    }

    @Override
    public ChatReply rewrite(RewritePrompt prompt) {
        return call(prompt.system(), prompt.user());
    }

    private ChatReply call(String system, String user) {
        ChatResponse response;
        try {
            response = client.call(new Prompt(List.of(new SystemMessage(system), new UserMessage(user))));
        } catch (RuntimeException ex) {
            throw new ChatProviderException(provider, model, isTimeout(ex), ex);
        }
        String text = response == null || response.getResult() == null ? null : response.getResult().getOutput().getText();
        return new ChatReply(text == null ? "" : text, provider, model);
    }

    private static boolean isTimeout(Throwable error) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = error; current != null && visited.add(current); current = current.getCause()) {
            if (current instanceof HttpTimeoutException
                    || current instanceof SocketTimeoutException
                    || current instanceof TimeoutException) {
                return true;
            }
            if (current instanceof InterruptedIOException
                    && current.getMessage() != null
                    && current.getMessage().toLowerCase(Locale.ROOT).contains("timeout")) {
                return true;
            }
        }
        return false;
    }
}
