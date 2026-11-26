package com.epfisztner.knowledgeassistant.conversation;

import com.jayway.jsonpath.JsonPath;
import com.epfisztner.knowledgeassistant.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class AskingQuestionsIT extends IntegrationTest {

    private static final String EXPERT = "expert-anna";

    private static long sourceId;
    private static long sourceVersionId;

    @BeforeEach
    void ingestMokusSzamlaOnce() {
        if (sourceVersionId != 0) {
            return;
        }
        fixtureSite.page("/answering/mokus-szamla/", """
                <html><head><title>Mókus Számla</title></head>
                <body><main>
                  <h1>Mókus Számla</h1>
                  <h2>Díjak</h2>
                  <p>A Mókus Számla számlavezetési díja havi 1 290 Ft. A bankkártya éves díja 0 Ft.</p>
                  <h2>Nyitás</h2>
                  <p>A számla online nyitható, személyesen nem.</p>
                </main></body></html>
                """);
        String report = new String(api.post().uri("/ingestion/runs?sourceKey=answer-mokus-szamla").exchange()
                .expectStatus().isCreated()
                .expectBody().returnResult().getResponseBodyContent());
        sourceId = JsonPath.<Number>read(report, "$.outcomes[0].sourceId").longValue();
        sourceVersionId = JsonPath.<Number>read(report, "$.outcomes[0].sourceVersionId").longValue();
    }

    @Test
    void aQuestionAboutAFixtureFactGetsAnAnswerCitingTheExactSourceVersionAndPassage() {
        long conversationId = startConversation(EXPERT);
        String question = "Mennyi a Mókus Számla számlavezetési díja?";

        api.post().uri("/conversations/{id}/turns", conversationId)
                .header("X-Expert-Id", EXPERT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("question", question))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.id").isNumber()
                .jsonPath("$.question").isEqualTo(question)
                .jsonPath("$.standaloneQuestion").isEqualTo(question)
                .jsonPath("$.status").isEqualTo("ANSWERED")
                .jsonPath("$.refusalReason").doesNotExist()
                .jsonPath("$.answer").value(String.class, answer -> assertThat(answer)
                        .contains("A Mókus Számla számlavezetési díja havi 1 290 Ft. [1]"))
                .jsonPath("$.latencyMs").isNumber()
                .jsonPath("$.chatProvider").isEqualTo("offline")
                .jsonPath("$.chatModel").isEqualTo("extractive-v1")
                .jsonPath("$.citations[0].marker").isEqualTo(1)
                .jsonPath("$.citations[0].sourceId").isEqualTo(sourceId)
                .jsonPath("$.citations[0].sourceVersionId").isEqualTo(sourceVersionId)
                .jsonPath("$.citations[0].versionNumber").isEqualTo(1)
                .jsonPath("$.citations[0].versionStatus").isEqualTo("CURRENT")
                .jsonPath("$.citations[0].sourceTitle").isEqualTo("Mókus Számla")
                .jsonPath("$.citations[0].url").isEqualTo(fixtureSite.url("/answering/mokus-szamla/"))
                .jsonPath("$.citations[0].bank").isEqualTo("Erdei Bank")
                .jsonPath("$.citations[0].bankProduct").isEqualTo("Mókus Számla")
                .jsonPath("$.citations[0].productCategory").isEqualTo("BANKSZAMLA")
                .jsonPath("$.citations[0].fetchedAt").exists()
                .jsonPath("$.citations[0].passageHeadingPath").isEqualTo(java.util.List.of("Mókus Számla", "Díjak"))
                .jsonPath("$.citations[0].passageText").value(String.class, text -> assertThat(text)
                        .contains("A Mókus Számla számlavezetési díja havi 1 290 Ft."));
    }

    @Test
    void aQuestionTypedWithoutAccentsFindsTheSamePassage() {
        String accented = ask(startConversation(EXPERT), "Mennyi a Mókus Számla számlavezetési díja?");
        String accentFree = ask(startConversation(EXPERT), "mennyi a mokus szamla szamlavezetesi dija");

        assertThat(JsonPath.<String>read(accentFree, "$.status")).isEqualTo("ANSWERED");
        assertThat(JsonPath.<Number>read(accentFree, "$.citations[0].passageId"))
                .isEqualTo(JsonPath.<Number>read(accented, "$.citations[0].passageId"));
        assertThat(JsonPath.<Number>read(accentFree, "$.citations[0].sourceVersionId").longValue())
                .isEqualTo(sourceVersionId);
    }

    @Test
    void aQuestionWithNoEvidenceInTheKnowledgeBaseIsRefused() {
        long conversationId = startConversation(EXPERT);

        api.post().uri("/conversations/{id}/turns", conversationId)
                .header("X-Expert-Id", EXPERT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("question", "Hány lába van a zsiráfnak?"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.status").isEqualTo("REFUSED")
                .jsonPath("$.refusalReason").isEqualTo("NO_EVIDENCE")
                .jsonPath("$.answer").value(String.class, answer -> assertThat(answer).contains("nem tartalmaz elegendő"))
                .jsonPath("$.citations").isEmpty()
                .jsonPath("$.latencyMs").isNumber();
    }

    @Test
    void conversationEndpointsRequireTheExpertIdHeader() {
        api.post().uri("/conversations")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of())
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentType("application/problem+json");

        long conversationId = startConversation(EXPERT);
        api.post().uri("/conversations/{id}/turns", conversationId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("question", "Mennyi a Mókus Számla számlavezetési díja?"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void anotherExpertsConversationIsNotFound() {
        long annasConversation = startConversation(EXPERT);

        api.post().uri("/conversations/{id}/turns", annasConversation)
                .header("X-Expert-Id", "expert-bela")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("question", "Mennyi a Mókus Számla számlavezetési díja?"))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void aBlankQuestionIsAValidationError() {
        api.post().uri("/conversations/{id}/turns", startConversation(EXPERT))
                .header("X-Expert-Id", EXPERT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("question", " "))
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType("application/problem+json");
    }

    @Test
    void aQuestionExceeding2000CharactersIsRejectedWithProblemDetails() {
        long conversationId = startConversation(EXPERT);
        String oversized = "a".repeat(2001);

        api.post().uri("/conversations/{id}/turns", conversationId)
                .header("X-Expert-Id", EXPERT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("question", oversized))
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.instance").isEqualTo("/api/v1/conversations/" + conversationId + "/turns")
                .jsonPath("$.errors[0].field").isEqualTo("question");
    }

    @Test
    void concurrentQuestionsInTheSameConversationGetDistinctSequentialOrdinals() throws Exception {
        long conversationId = startConversation(EXPERT);
        int requests = 8;
        CountDownLatch ready = new CountDownLatch(requests);
        CountDownLatch start = new CountDownLatch(1);
        List<Integer> ordinals = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(requests)) {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 1; i <= requests; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    String body = ask(conversationId, "Mennyi a Mókus Számla számlavezetési díja? #" + index);
                    return JsonPath.<Number>read(body, "$.ordinal").intValue();
                }));
            }
            ready.await();
            start.countDown();
            for (Future<Integer> future : futures) {
                ordinals.add(future.get());
            }
        }

        List<Integer> expected = IntStream.rangeClosed(1, requests).boxed().toList();
        assertThat(ordinals).containsExactlyInAnyOrderElementsOf(expected);

        api.get().uri("/conversations/{id}", conversationId)
                .header("X-Expert-Id", EXPERT)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.turns[*].ordinal").isEqualTo(expected);
    }

    private String ask(long conversationId, String question) {
        return new String(api.post().uri("/conversations/{id}/turns", conversationId)
                .header("X-Expert-Id", EXPERT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("question", question))
                .exchange()
                .expectStatus().isCreated()
                .expectBody().returnResult().getResponseBodyContent());
    }

    private long startConversation(String expertId) {
        String body = new String(api.post().uri("/conversations")
                .header("X-Expert-Id", expertId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of())
                .exchange()
                .expectStatus().isCreated()
                .expectBody().returnResult().getResponseBodyContent());
        return JsonPath.<Number>read(body, "$.id").longValue();
    }
}
