package com.epfisztner.knowledgeassistant.provider;

import com.jayway.jsonpath.JsonPath;
import com.epfisztner.knowledgeassistant.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The application started with a real chat provider selected by configuration. The provider's
 * Spring AI client talks to a fake of the provider's HTTP API on the local fixture site, so no
 * network call leaves the machine. Subclasses select the provider and fake its wire format.
 */
abstract class RealChatProviderIT extends IntegrationTest {

    static final String MODEL = "fixture-model";
    private static final String EXPERT = "expert-provider";
    private static final String ANSWER = "A Hörcsög Számla számlavezetési díja havi 2 345 Ft. [1]";
    private static final String STANDALONE = "Mennyi a Hörcsög Számla bankkártyájának éves díja?";

    /** {@code knowledge.chat.provider} value selected by the subclass. */
    abstract String provider();

    /** Path of the provider's chat endpoint on the fixture site. */
    abstract String endpointPath();

    /** The provider's JSON response carrying {@code text} as the model's output. */
    abstract String responseWith(String text);

    @BeforeEach
    void fakeProviderAndIngestHorcsogSzamla() {
        // The answer prompt carries the decline sentinel; anything else is a rewrite request.
        fixtureSite.api(endpointPath(), request -> responseWith(
                request.contains("NINCS_ELEG_BIZONYITEK") ? ANSWER : STANDALONE));
        fixtureSite.page("/providers/horcsog-szamla/", """
                <html><head><title>Hörcsög Számla</title></head>
                <body><main>
                  <h1>Hörcsög Számla</h1>
                  <h2>Díjak</h2>
                  <p>A Hörcsög Számla számlavezetési díja havi 2 345 Ft. A bankkártya éves díja 0 Ft.</p>
                </main></body></html>
                """);
        api.post().uri("/ingestion/runs?sourceKey=provider-horcsog-szamla").exchange().expectStatus().isCreated();
    }

    @Test
    void answersAndRewritesComeFromTheConfiguredProviderWhichIsRecordedOnEachTurn() {
        long conversationId = startConversation();
        int requestsBefore = fixtureSite.requests(endpointPath()).size();

        String first = ask(conversationId, "Mennyi a Hörcsög Számla számlavezetési díja?");
        assertThat(JsonPath.<String>read(first, "$.status")).isEqualTo("ANSWERED");
        assertThat(JsonPath.<String>read(first, "$.answer")).isEqualTo(ANSWER);
        assertThat(JsonPath.<String>read(first, "$.citations[0].bankProduct")).isEqualTo("Hörcsög Számla");
        assertThat(JsonPath.<String>read(first, "$.chatProvider")).isEqualTo(provider());
        assertThat(JsonPath.<String>read(first, "$.chatModel")).isEqualTo(MODEL);
        // a first Turn is its own Standalone Question: only the answer request reached the model
        List<String> firstRequests = fixtureSite.requests(endpointPath()).subList(requestsBefore,
                fixtureSite.requests(endpointPath()).size());
        assertThat(firstRequests).singleElement().satisfies(request -> assertThat(request)
                .contains("NINCS_ELEG_BIZONYITEK").contains(MODEL).contains("Hörcsög Számla"));

        String followUp = ask(conversationId, "És a kártyáé?");
        assertThat(JsonPath.<String>read(followUp, "$.standaloneQuestion")).isEqualTo(STANDALONE);
        assertThat(JsonPath.<String>read(followUp, "$.chatProvider")).isEqualTo(provider());

        // the versioned rewrite prompt (system and user text) went to the configured model
        List<String> all = fixtureSite.requests(endpointPath());
        String rewriteRequest = all.subList(requestsBefore + 1, all.size()).stream()
                .filter(request -> !request.contains("NINCS_ELEG_BIZONYITEK")).findFirst().orElseThrow();
        assertThat(rewriteRequest)
                .contains(MODEL)
                .contains("önálló kérdéssé")
                .contains("Kérdés: Mennyi a Hörcsög Számla számlavezetési díja?")
                .contains("Válasz: " + ANSWER)
                .contains("Új kérdés: És a kártyáé?");

        api.get().uri("/turns/{id}/audit", JsonPath.<Number>read(followUp, "$.id").longValue())
                .header("X-Expert-Id", EXPERT).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.rewrite.promptVersion").isEqualTo("rewrite-v1")
                .jsonPath("$.rewrite.rawOutput").isEqualTo(STANDALONE)
                .jsonPath("$.rewrite.chatProvider").isEqualTo(provider())
                .jsonPath("$.rewrite.chatModel").isEqualTo(MODEL)
                .jsonPath("$.rewrite.fellBackToJoin").isEqualTo(false);
    }

    @Test
    void aRefusalWithoutAModelCallStillRecordsTheConfiguredProvider() {
        String refused = ask(startConversation(), "Hány lába van a zsiráfnak?");

        assertThat(JsonPath.<String>read(refused, "$.refusalReason")).isEqualTo("NO_EVIDENCE");
        assertThat(JsonPath.<String>read(refused, "$.chatProvider")).isEqualTo(provider());
        assertThat(JsonPath.<String>read(refused, "$.chatModel")).isEqualTo(MODEL);
    }

    @Test
    void anUpstreamProviderErrorReturnsBadGatewayProblemDetailWithoutLeakingDetails() {
        long conversationId = startConversation();
        fixtureSite.apiFailure(endpointPath(), 503,
                "{\"error\":{\"message\":\"upstream secret-payload with test-key\"}}");

        expectUpstreamProblem(conversationId, "Mennyi a Hörcsög Számla számlavezetési díja?",
                HttpStatus.BAD_GATEWAY, "secret-payload");

        api.get().uri("/conversations/{id}", conversationId)
                .header("X-Expert-Id", EXPERT)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.turns").isEmpty();
    }

    @Test
    void anUpstreamProviderTimeoutReturnsGatewayTimeoutProblemDetail() {
        long conversationId = startConversation();
        fixtureSite.apiDelayed(endpointPath(), Duration.ofSeconds(3), request -> responseWith(ANSWER));

        expectUpstreamProblem(conversationId, "Mennyi a Hörcsög Számla számlavezetési díja?",
                HttpStatus.GATEWAY_TIMEOUT);

        api.get().uri("/conversations/{id}", conversationId)
                .header("X-Expert-Id", EXPERT)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.turns").isEmpty();
    }

    @Test
    void anUpstreamProviderFailureOrTimeoutDuringFollowUpRewriteReturnsProblemDetail() {
        long conversationId = startConversation();
        ask(conversationId, "Mennyi a Hörcsög Számla számlavezetési díja?");

        fixtureSite.apiFailure(endpointPath(), 429,
                "{\"error\":{\"message\":\"rate limit secret-rewrite with test-key\"}}");
        expectUpstreamProblem(conversationId, "És a kártyáé?", HttpStatus.BAD_GATEWAY, "secret-rewrite");

        fixtureSite.apiDelayed(endpointPath(), Duration.ofSeconds(3), request -> responseWith(STANDALONE));
        expectUpstreamProblem(conversationId, "És a kártyáé?", HttpStatus.GATEWAY_TIMEOUT);

        api.get().uri("/conversations/{id}", conversationId)
                .header("X-Expert-Id", EXPERT)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.turns.length()").isEqualTo(1);
    }

    private void expectUpstreamProblem(long conversationId, String question, HttpStatus expectedStatus,
                                       String... forbiddenDetails) {
        api.post().uri("/conversations/{id}/turns", conversationId)
                .header("X-Expert-Id", EXPERT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("question", question))
                .exchange()
                .expectStatus().isEqualTo(expectedStatus)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(expectedStatus.value())
                .jsonPath("$.title").isEqualTo(expectedStatus.getReasonPhrase())
                .jsonPath("$.instance").isEqualTo("/api/v1/conversations/" + conversationId + "/turns")
                .jsonPath("$.detail").value(String.class, detail -> {
                    assertThat(detail).isNotBlank().doesNotContain("test-key").doesNotContain("Exception");
                    for (String forbidden : forbiddenDetails) {
                        assertThat(detail).doesNotContain(forbidden);
                    }
                });
    }

    static String json(String text) {
        return '"' + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + '"';
    }

    private String ask(long conversationId, String question) {
        return new String(api.post().uri("/conversations/{id}/turns", conversationId)
                .header("X-Expert-Id", EXPERT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("question", question))
                .exchange()
                .expectStatus().isCreated()
                .expectBody().returnResult().getResponseBodyContent(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private long startConversation() {
        String body = new String(api.post().uri("/conversations")
                .header("X-Expert-Id", EXPERT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of())
                .exchange()
                .expectStatus().isCreated()
                .expectBody().returnResult().getResponseBodyContent());
        return JsonPath.<Number>read(body, "$.id").longValue();
    }
}
