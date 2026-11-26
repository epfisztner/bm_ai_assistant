package com.epfisztner.knowledgeassistant.ingestion;

import com.epfisztner.knowledgeassistant.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs with seeding enabled against its own registry, so it gets its own application context
 * and an empty database. The fixture pages exist before the context starts.
 */
@TestPropertySource(properties = {
        "knowledge.ingestion.seed-on-startup=true",
        "knowledge.source-registry.location=classpath:registry/seeding-registry.yaml"
})
class StartupSeedingIT extends IntegrationTest {

    static {
        fixtureSite.page("/seeding/szamla/", "<html><body><main><h1>Mag Számla</h1><p>Havi díj 0 Ft.</p></main></body></html>");
        fixtureSite.page("/seeding/kolcson/", "<html><body><main><h1>Mag Kölcsön</h1><p>THM 9,9%.</p></main></body></html>");
    }

    @Test
    void anEmptyKnowledgeBaseIsSeededFromTheSourceRegistryBeforeTheServiceIsReady() {
        server.get().uri("/actuator/health/readiness").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP");

        api.get().uri("/sources").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[*].key").value(List.class, keys -> assertThat(keys).containsExactly("seed-kolcson", "seed-szamla"))
                .jsonPath("$[*].currentVersion.versionNumber").isEqualTo(List.of(1, 1))
                .jsonPath("$[*].lastFetchOutcome").isEqualTo(List.of("NEW_VERSION", "NEW_VERSION"));
    }
}
