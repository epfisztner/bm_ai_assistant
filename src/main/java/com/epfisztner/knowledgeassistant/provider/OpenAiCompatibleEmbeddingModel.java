package com.epfisztner.knowledgeassistant.provider;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.epfisztner.knowledgeassistant.embedding.EmbeddingModelPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;

/**
 * Embedding adapter for any OpenAI-compatible {@code POST /embeddings} endpoint (OpenAI, LM Studio,
 * Ollama, vLLM). A plain HTTP client keeps it independent of the chat provider and its client
 * library. The model id stored per Passage is the model name, plus {@code @dimensions} when a
 * dimension is configured (a different dimension means incomparable vectors).
 */
@Component
@ConditionalOnProperty(name = "knowledge.embedding.provider", havingValue = "openai-compatible")
class OpenAiCompatibleEmbeddingModel implements EmbeddingModelPort {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record EmbeddingRequest(String model, List<String> input, Integer dimensions) {
    }

    private record EmbeddingResponse(List<Item> data) {
        private record Item(int index, float[] embedding) {
        }
    }

    private final RestClient client;
    private final String model;
    private final Integer dimensions;

    OpenAiCompatibleEmbeddingModel(@Value("${knowledge.embedding.openai-compatible.base-url}") String baseUrl,
                                   @Value("${knowledge.embedding.openai-compatible.model}") String model,
                                   @Value("${knowledge.embedding.openai-compatible.api-key:}") String apiKey,
                                   @Value("${knowledge.embedding.openai-compatible.dimensions:#{null}}") Integer dimensions,
                                   @Value("${knowledge.embedding.openai-compatible.timeout:60s}") Duration timeout) {
        this.model = model;
        this.dimensions = dimensions;
        var requestFactory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(timeout).build());
        requestFactory.setReadTimeout(timeout);
        RestClient.Builder builder = RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
        if (!apiKey.isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }
        this.client = builder.build();
    }

    @Override
    public String modelId() {
        return dimensions == null ? model : model + "@" + dimensions;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        EmbeddingResponse response = client.post().uri("/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new EmbeddingRequest(model, texts, dimensions))
                .retrieve()
                .body(EmbeddingResponse.class);
        if (response == null || response.data() == null || response.data().size() != texts.size()) {
            throw new IllegalStateException("Embedding endpoint returned no or too few vectors");
        }
        return response.data().stream()
                .sorted(Comparator.comparingInt(EmbeddingResponse.Item::index))
                .map(EmbeddingResponse.Item::embedding)
                .toList();
    }
}
