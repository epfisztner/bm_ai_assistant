package com.epfisztner.knowledgeassistant.provider;

import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {
        "knowledge.chat.provider=anthropic",
        "knowledge.chat.anthropic.base-url=${fixture-site.base-url}/providers/anthropic",
        "knowledge.chat.anthropic.model=" + RealChatProviderIT.MODEL,
        "knowledge.chat.anthropic.api-key=test-key",
        "knowledge.chat.anthropic.timeout=1s",
        "knowledge.chat.anthropic.max-retries=0"})
class AnthropicProviderIT extends RealChatProviderIT {

    @Override
    String provider() {
        return "anthropic";
    }

    @Override
    String endpointPath() {
        return "/providers/anthropic/v1/messages";
    }

    @Override
    String responseWith(String text) {
        return """
                {"id": "msg_1", "type": "message", "role": "assistant", "model": "%s",
                 "content": [{"type": "text", "text": %s}],
                 "stop_reason": "end_turn", "stop_sequence": null,
                 "usage": {"input_tokens": 10, "output_tokens": 10}}
                """.formatted(MODEL, json(text));
    }
}
