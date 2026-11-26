package com.epfisztner.knowledgeassistant.conversation;

import com.jayway.jsonpath.JsonPath;
import com.epfisztner.knowledgeassistant.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProductCategoryFilterIT extends IntegrationTest {

    private static final String EXPERT = "expert-cecilia";
    private static final String SHARED_QUESTION = "Mennyi a bagolydíja?";

    private static boolean ingested;

    @BeforeEach
    void ingestFixturesInTwoProductCategoriesOnce() {
        if (ingested) {
            return;
        }
        fixtureSite.page("/filtering/bagoly-lakashitel/", """
                <html><head><title>Bagoly Lakáshitel</title></head>
                <body><main>
                  <h1>Bagoly Lakáshitel</h1>
                  <h2>Díjak</h2>
                  <p>A Bagoly Lakáshitel bagolydíja egyszeri 15 000 Ft.</p>
                  <h2>Fészek</h2>
                  <p>A fészekpótlék mértéke a kölcsönösszeg két ezreléke.</p>
                </main></body></html>
                """);
        fixtureSite.page("/filtering/bagoly-szamla/", """
                <html><head><title>Bagoly Számla</title></head>
                <body><main>
                  <h1>Bagoly Számla</h1>
                  <h2>Díjak</h2>
                  <p>A Bagoly Számla bagolydíja havi 300 Ft.</p>
                </main></body></html>
                """);
        ingest("filter-bagoly-lakashitel");
        ingest("filter-bagoly-szamla");
        ingested = true;
    }

    @Test
    void aConversationDefaultCategoryRestrictsEveryTurnToThatCategory() {
        long conversationId = startConversation("BANKSZAMLA");

        String turn = ask(conversationId, SHARED_QUESTION, null);

        assertThat(JsonPath.<String>read(turn, "$.status")).as(turn).isEqualTo("ANSWERED");
        assertThat(JsonPath.<String>read(turn, "$.productCategory")).isEqualTo("BANKSZAMLA");
        assertThat(JsonPath.<List<String>>read(turn, "$.citations[*].productCategory")).containsOnly("BANKSZAMLA");
        assertThat(JsonPath.<String>read(turn, "$.answer")).contains("havi 300 Ft").doesNotContain("15 000 Ft");
    }

    @Test
    void theConversationReportsItsDefaultCategory() {
        api.post().uri("/conversations")
                .header("X-Expert-Id", EXPERT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("productCategory", "LAKASHITEL"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody().jsonPath("$.productCategory").isEqualTo("LAKASHITEL");
    }

    @Test
    void aTurnOverrideReplacesTheDefaultForThatTurnOnly() {
        long conversationId = startConversation("BANKSZAMLA");

        String overridden = ask(conversationId, SHARED_QUESTION, "LAKASHITEL");
        String next = ask(conversationId, SHARED_QUESTION, null);

        assertThat(JsonPath.<String>read(overridden, "$.productCategory")).isEqualTo("LAKASHITEL");
        assertThat(JsonPath.<List<String>>read(overridden, "$.citations[*].productCategory")).containsOnly("LAKASHITEL");
        assertThat(JsonPath.<String>read(overridden, "$.answer")).contains("15 000 Ft").doesNotContain("havi 300 Ft");
        assertThat(JsonPath.<String>read(next, "$.productCategory")).isEqualTo("BANKSZAMLA");
        assertThat(JsonPath.<List<String>>read(next, "$.citations[*].productCategory")).containsOnly("BANKSZAMLA");
    }

    @Test
    void withoutAFilterEveryCategoryIsSearched() {
        long conversationId = startConversation(null);

        String turn = ask(conversationId, SHARED_QUESTION, null);

        assertThat(JsonPath.<String>read(turn, "$.status")).as(turn).isEqualTo("ANSWERED");
        assertThat(JsonPath.<Object>read(turn, "$.productCategory")).isNull();
        assertThat(JsonPath.<List<String>>read(turn, "$.citations[*].productCategory"))
                .contains("BANKSZAMLA", "LAKASHITEL");
    }

    @Test
    void aFilteredTurnRefusesRatherThanAnswerFromAnotherCategory() {
        String unfiltered = ask(startConversation(null), "Mekkora a fészekpótlék?", null);
        assertThat(JsonPath.<String>read(unfiltered, "$.status")).as(unfiltered).isEqualTo("ANSWERED");

        String filtered = ask(startConversation("BANKSZAMLA"), "Mekkora a fészekpótlék?", null);

        assertThat(JsonPath.<String>read(filtered, "$.status")).as(filtered).isEqualTo("REFUSED");
        assertThat(JsonPath.<String>read(filtered, "$.refusalReason")).isEqualTo("NO_EVIDENCE");
        assertThat(JsonPath.<String>read(filtered, "$.productCategory")).isEqualTo("BANKSZAMLA");
        assertThat(JsonPath.<List<Object>>read(filtered, "$.citations")).isEmpty();
    }

    @Test
    void anUnknownProductCategoryCodeIsAValidationError() {
        api.post().uri("/conversations")
                .header("X-Expert-Id", EXPERT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("productCategory", "AUTOHITEL"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType("application/problem+json")
                .expectBody()
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.errors[0].field").isEqualTo("productCategory")
                .jsonPath("$.errors[0].message").value(String.class, message -> assertThat(message)
                        .contains("AUTOHITEL").contains("LAKASHITEL, SZEMELYI_KOLCSON, BANKSZAMLA"));

        api.post().uri("/conversations/{id}/turns", startConversation(null))
                .header("X-Expert-Id", EXPERT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("question", SHARED_QUESTION, "productCategory", "AUTOHITEL"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType("application/problem+json")
                .expectBody()
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.errors[0].field").isEqualTo("productCategory")
                .jsonPath("$.errors[0].message").value(String.class, message -> assertThat(message)
                        .contains("AUTOHITEL").contains("LAKASHITEL, SZEMELYI_KOLCSON, BANKSZAMLA"));
    }

    private void ingest(String sourceKey) {
        api.post().uri("/ingestion/runs?sourceKey=" + sourceKey).exchange()
                .expectStatus().isCreated()
                .expectBody().jsonPath("$.outcomes[0].outcome").isEqualTo("NEW_VERSION");
    }

    private long startConversation(String productCategory) {
        Map<String, Object> body = new HashMap<>();
        if (productCategory != null) {
            body.put("productCategory", productCategory);
        }
        String response = new String(api.post().uri("/conversations")
                .header("X-Expert-Id", EXPERT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange()
                .expectStatus().isCreated()
                .expectBody().returnResult().getResponseBodyContent());
        return JsonPath.<Number>read(response, "$.id").longValue();
    }

    private String ask(long conversationId, String question, String productCategory) {
        Map<String, Object> body = new HashMap<>();
        body.put("question", question);
        if (productCategory != null) {
            body.put("productCategory", productCategory);
        }
        return new String(api.post().uri("/conversations/{id}/turns", conversationId)
                .header("X-Expert-Id", EXPERT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange()
                .expectStatus().isCreated()
                .expectBody().returnResult().getResponseBodyContent());
    }
}
