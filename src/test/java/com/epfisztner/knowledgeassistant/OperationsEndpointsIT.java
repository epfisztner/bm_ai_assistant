package com.epfisztner.knowledgeassistant;

import com.epfisztner.knowledgeassistant.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;

class OperationsEndpointsIT extends IntegrationTest {

    @Test
    void reportsHealthAndReadinessForKubernetesProbes() {
        server.get().uri("/actuator/health/liveness").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP");
        server.get().uri("/actuator/health/readiness").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP");
    }

    @Test
    void publishesTheOpenApiDocumentDescribingTheApi() {
        server.get().uri("/v3/api-docs").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.paths['/api/v1/product-categories'].get").exists()
                .jsonPath("$.paths['/api/v1/feedback'].get").exists()
                .jsonPath("$.paths['/api/v1/feedback'].get.description").value(String.class,
                        description -> assertThat(description).contains("Knowledge Owner").contains("BFF"));
    }

    @Test
    void servesSwaggerUi() {
        server.get().uri("/swagger-ui/index.html").exchange()
                .expectStatus().isOk();
    }

    @Test
    void servesTheApiTestConsoleAtTheRoot() {
        server.get().uri("/").exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
                .expectBody(String.class).value(body -> assertThat(body).contains("<title>API tesztkonzol"));
    }
}
