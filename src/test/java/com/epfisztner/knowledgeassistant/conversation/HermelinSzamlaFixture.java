package com.epfisztner.knowledgeassistant.conversation;

import com.jayway.jsonpath.JsonPath;
import com.epfisztner.knowledgeassistant.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.MediaType;

import java.util.Map;

/**
 * Shared fixture for the audit and feedback tests: the Hermelin Számla Source (registry key
 * {@code audit-hermelin-szamla}), ingested once, and a helper that asks in a fresh BANKSZAMLA Conversation.
 */
abstract class HermelinSzamlaFixture extends IntegrationTest {

    static final String EXPERT = "expert-aurel";
    static final String QUESTION = "Mennyi a Hermelin Számla számlavezetési díja?";

    static long sourceId;
    static long sourceVersionId;

    /** A Turn as returned by {@code POST /conversations/{id}/turns}. */
    record AskedTurn(long id, String json) {

        <T> T read(String path) {
            return JsonPath.read(json, path);
        }
    }

    @BeforeEach
    void ingestHermelinSzamlaOnce() {
        if (sourceId != 0) {
            return;
        }
        fixtureSite.page("/audit/hermelin-szamla/", """
                <html><head><title>Hermelin Számla</title></head>
                <body><main>
                  <h1>Hermelin Számla</h1>
                  <h2>Díjak</h2>
                  <p>A Hermelin Számla számlavezetési díja havi 1 357 Ft.</p>
                  <h2>Nyitás</h2>
                  <p>A Hermelin Számla online nyitható.</p>
                </main></body></html>
                """);
        String report = new String(api.post().uri("/ingestion/runs?sourceKey=audit-hermelin-szamla").exchange()
                .expectStatus().isCreated()
                .expectBody().returnResult().getResponseBodyContent());
        sourceId = JsonPath.<Number>read(report, "$.outcomes[0].sourceId").longValue();
        sourceVersionId = JsonPath.<Number>read(report, "$.outcomes[0].sourceVersionId").longValue();
    }

    long startConversation(Map<String, ?> body) {
        return startConversation(EXPERT, body);
    }

    long startConversation(String expertId, Map<String, ?> body) {
        String conversation = new String(api.post().uri("/conversations")
                .header("X-Expert-Id", expertId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange()
                .expectStatus().isCreated()
                .expectBody().returnResult().getResponseBodyContent());
        return JsonPath.<Number>read(conversation, "$.id").longValue();
    }

    AskedTurn askInBankszamlaConversation(String question) {
        return ask(startConversation(Map.of("productCategory", "BANKSZAMLA")), question);
    }

    /** Asks the next Question in the Conversation of {@code previous}. */
    AskedTurn askFollowUp(AskedTurn previous, String question) {
        return ask(previous.<Number>read("$.conversationId").longValue(), question);
    }

    AskedTurn ask(long conversationId, String question) {
        return ask(conversationId, question, EXPERT);
    }

    AskedTurn ask(long conversationId, String question, String expertId) {
        String turn = new String(api.post().uri("/conversations/{id}/turns", conversationId)
                .header("X-Expert-Id", expertId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("question", question))
                .exchange()
                .expectStatus().isCreated()
                .expectBody().returnResult().getResponseBodyContent());
        return new AskedTurn(JsonPath.<Number>read(turn, "$.id").longValue(), turn);
    }
}
