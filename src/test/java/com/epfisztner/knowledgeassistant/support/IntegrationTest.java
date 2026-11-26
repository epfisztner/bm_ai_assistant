package com.epfisztner.knowledgeassistant.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Base class for integration tests: boots the full application on a random port against
 * Testcontainers PostgreSQL + pgvector, with a local {@link FixtureSite} for Source pages.
 * <p>
 * Tests exercise the service only through its HTTP API: {@link #api} is bound to
 * {@code /api/v1}, {@link #server} to the server root (Actuator, OpenAPI, Swagger UI).
 * The fixture site's base URL is exposed as the {@code fixture-site.base-url} property so
 * test configuration (e.g. a test Source Registry) can point at it.
 * <p>
 * {@link #chatModel} dictates the chat model's output for the next Turn; unscripted, the
 * offline adapter answers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, ScriptedChatModelConfiguration.class})
@ActiveProfiles("test")
public abstract class IntegrationTest {

    protected static final FixtureSite fixtureSite = FixtureSite.shared();

    @DynamicPropertySource
    static void fixtureSiteProperties(DynamicPropertyRegistry registry) {
        registry.add("fixture-site.base-url", fixtureSite::baseUrl);
    }

    @LocalServerPort
    private int port;

    protected RestTestClient api;
    protected RestTestClient server;

    @Autowired
    protected ScriptedChatModel chatModel;

    @BeforeEach
    void setUpHttpClients() {
        chatModel.reset();
        server = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        api = RestTestClient.bindToServer().baseUrl("http://localhost:" + port + "/api/v1").build();
    }
}
