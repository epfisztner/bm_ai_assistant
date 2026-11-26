package com.epfisztner.knowledgeassistant.generation;

/**
 * Thrown by a real {@link ChatModelPort} adapter when the configured upstream chat provider times
 * out or fails with an HTTP, API or transport error. Translated to an RFC 9457 Problem Detail
 * ({@code 504} or {@code 502}) at the HTTP boundary; never turned into an unverified fallback
 * Answer (ADR-0002).
 */
public class ChatProviderException extends RuntimeException {

    private final ChatProvider provider;
    private final String model;
    private final boolean timeout;

    public ChatProviderException(ChatProvider provider, String model, boolean timeout, Throwable cause) {
        super("Chat provider " + provider + " (" + model + ") " + (timeout ? "timed out" : "failed"), cause);
        this.provider = provider;
        this.model = model;
        this.timeout = timeout;
    }

    public ChatProvider provider() {
        return provider;
    }

    public String model() {
        return model;
    }

    public boolean isTimeout() {
        return timeout;
    }
}
