package com.epfisztner.knowledgeassistant.provider;

import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {
        "knowledge.chat.provider=openai-compatible",
        "knowledge.chat.openai-compatible.base-url=${fixture-site.base-url}/providers/openai/v1",
        "knowledge.chat.openai-compatible.model=" + RealChatProviderIT.MODEL,
        "knowledge.chat.openai-compatible.api-key=test-key",
        "knowledge.chat.openai-compatible.timeout=1s",
        "knowledge.chat.openai-compatible.max-retries=0"})
class OpenAiCompatibleProviderIT extends RealChatProviderIT {

    @Override
    String provider() {
        return "openai-compatible";
    }

    @Override
    String endpointPath() {
        return "/providers/openai/v1/chat/completions";
    }

    @Override
    String responseWith(String text) {
        return """
                {"id": "chatcmpl-1", "object": "chat.completion", "created": 1767225600, "model": "%s",
                 "choices": [{"index": 0, "finish_reason": "stop",
                              "message": {"role": "assistant", "content": %s}}],
                 "usage": {"prompt_tokens": 10, "completion_tokens": 10, "total_tokens": 20}}
                """.formatted(MODEL, json(text));
    }
}
