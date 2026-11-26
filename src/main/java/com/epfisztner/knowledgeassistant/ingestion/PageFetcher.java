package com.epfisztner.knowledgeassistant.ingestion;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.net.http.HttpClient;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Fetches a Source page over HTTP, politely: an identifying user agent and connect/read timeouts.
 * Non-2xx responses and I/O errors surface as exceptions, which ingestion reports as {@code FAILED}.
 */
@Component
class PageFetcher {

    private final RestClient client;

    PageFetcher(@Value("${knowledge.ingestion.user-agent}") String userAgent,
                @Value("${knowledge.ingestion.fetch-timeout}") Duration timeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        this.client = RestClient.builder()
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.USER_AGENT, userAgent)
                .defaultHeader(HttpHeaders.ACCEPT, "text/html,application/xhtml+xml")
                .build();
    }

    /**
     * Returns the raw page content.
     */
    String fetch(String url) {
        ResponseEntity<byte[]> response = client.get().uri(url).retrieve().toEntity(byte[].class);
        byte[] bytes = response.getBody();
        if (bytes == null || bytes.length == 0) {
            throw new IllegalStateException("Empty response from " + url);
        }
        MediaType contentType = response.getHeaders().getContentType();
        Charset charset = contentType != null && contentType.getCharset() != null ? contentType.getCharset() : StandardCharsets.UTF_8;
        return new String(bytes, charset);
    }
}
