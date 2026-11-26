package com.epfisztner.knowledgeassistant.generation;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

/**
 * The chat providers the service can be configured with ({@code knowledge.chat.provider}). The
 * {@link #code()} is the value used in configuration, the API and the database.
 */
public enum ChatProvider {

    OFFLINE("offline"),
    OPENAI_COMPATIBLE("openai-compatible"),
    ANTHROPIC("anthropic");

    private final String code;

    ChatProvider(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }

    public static ChatProvider fromCode(String code) {
        return Arrays.stream(values()).filter(p -> p.code.equals(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown chat provider: " + code));
    }

    @Override
    public String toString() {
        return code;
    }
}
