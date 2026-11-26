package com.epfisztner.knowledgeassistant.provider;

import com.epfisztner.knowledgeassistant.generation.ChatModelPort;
import com.epfisztner.knowledgeassistant.generation.ChatProvider;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Real chat providers, selected with {@code knowledge.chat.provider} (the offline adapter is the
 * default, see {@link OfflineChatModel}). Each builds a Spring AI chat model client (ADR-0005)
 * from its own {@code knowledge.chat.<provider>.*} settings; building it makes no network call.
 */
@Configuration(proxyBeanMethods = false)
class ChatProviderConfiguration {

    /**
     * Any OpenAI-compatible Chat Completions endpoint: OpenAI, LM Studio, Ollama, vLLM.
     */
    @Bean
    @ConditionalOnProperty(name = "knowledge.chat.provider", havingValue = "openai-compatible")
    ChatModelPort openAiCompatibleChatModel(
            @Value("${knowledge.chat.openai-compatible.base-url}") String baseUrl,
            @Value("${knowledge.chat.openai-compatible.model}") String model,
            @Value("${knowledge.chat.openai-compatible.api-key:}") String apiKey,
            @Value("${knowledge.chat.openai-compatible.temperature:0.0}") Double temperature,
            @Value("${knowledge.chat.openai-compatible.max-tokens:4096}") Integer maxTokens,
            @Value("${knowledge.chat.openai-compatible.timeout:120s}") Duration timeout,
            @Value("${knowledge.chat.openai-compatible.max-retries:2}") Integer maxRetries) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .baseUrl(baseUrl)
                // local servers (LM Studio, Ollama) ignore the key, but the client requires one
                .apiKey(apiKey.isBlank() ? "not-needed" : apiKey)
                .model(model)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .timeout(timeout)
                .maxRetries(maxRetries)
                .build();
        return new SpringAiChatModel(ChatProvider.OPENAI_COMPATIBLE, model, OpenAiChatModel.builder().options(options).build());
    }

    /**
     * Anthropic's Messages API. No sampling parameters are sent: current Claude models reject
     * {@code temperature}.
     */
    @Bean
    @ConditionalOnProperty(name = "knowledge.chat.provider", havingValue = "anthropic")
    ChatModelPort anthropicChatModel(
            @Value("${knowledge.chat.anthropic.base-url}") String baseUrl,
            @Value("${knowledge.chat.anthropic.model}") String model,
            @Value("${knowledge.chat.anthropic.api-key}") String apiKey,
            @Value("${knowledge.chat.anthropic.max-tokens:16000}") Integer maxTokens,
            @Value("${knowledge.chat.anthropic.timeout:300s}") Duration timeout,
            @Value("${knowledge.chat.anthropic.max-retries:2}") Integer maxRetries) {
        AnthropicChatOptions options = AnthropicChatOptions.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .model(model)
                .maxTokens(maxTokens)
                .timeout(timeout)
                .maxRetries(maxRetries)
                .build();
        return new SpringAiChatModel(ChatProvider.ANTHROPIC, model, AnthropicChatModel.builder().options(options).build());
    }
}
