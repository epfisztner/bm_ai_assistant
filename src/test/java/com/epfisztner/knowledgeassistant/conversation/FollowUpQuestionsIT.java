package com.epfisztner.knowledgeassistant.conversation;

import com.jayway.jsonpath.JsonPath;
import com.epfisztner.knowledgeassistant.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FollowUpQuestionsIT extends IntegrationTest {

    private static final String EXPERT = "expert-csilla";

    private static final String VIDRA_PASSAGES = "$.citations[?(@.bankProduct == 'Vidra Számla')].passageId";

    private static boolean hodIngested;

    @BeforeEach
    void ingestHodSzamlaOnce() {
        if (hodIngested) {
            return;
        }
        fixtureSite.page("/follow-up/hod-szamla/", """
                <html><head><title>Hód Számla</title></head>
                <body><main>
                  <h1>Hód Számla</h1>
                  <h2>Díjak</h2>
                  <p>A Hód Számla számlavezetési díja havi 1 490 Ft. A Hód bankkártya éves díja 2 500 Ft.</p>
                </main></body></html>
                """);
        ingest("followup-hod-szamla");
        hodIngested = true;
    }

    @Test
    void aFollowUpIsSearchedAsAStandaloneQuestionBuiltFromTheConversation() {
        long conversationId = startConversation();
        String first = "Mennyi a Hód Számla számlavezetési díja?";
        String followUp = "És mennyi a bankkártya éves díja?";

        String turn1 = ask(conversationId, first);
        assertThat(JsonPath.<String>read(turn1, "$.standaloneQuestion")).isEqualTo(first);

        String turn2 = ask(conversationId, followUp);
        assertThat(JsonPath.<String>read(turn2, "$.question")).isEqualTo(followUp);
        // offline rewrite: the previous Standalone Question joined with the new Question
        assertThat(JsonPath.<String>read(turn2, "$.standaloneQuestion")).isEqualTo(first + " " + followUp);
        assertThat(JsonPath.<String>read(turn2, "$.status")).isEqualTo("ANSWERED");
        assertThat(JsonPath.<String>read(turn2, "$.answer")).contains("A Hód bankkártya éves díja 2 500 Ft.");
        assertThat(JsonPath.<List<String>>read(turn2, "$.citations[*].bankProduct")).contains("Hód Számla");
    }

    @Test
    void aFollowUpCitesOnlyPassagesRetrievedInItsOwnTurnNeverTheEarlierAnswer() {
        fixtureSite.page("/follow-up/vidra-szamla/", vidraSzamla("990 Ft"));
        ingest("followup-vidra-szamla");
        long conversationId = startConversation();

        String turn1 = ask(conversationId, "Mennyi a Vidra Számla számlavezetési díja?");
        assertThat(JsonPath.<String>read(turn1, "$.answer")).contains("havi 990 Ft");
        List<Number> turn1VidraPassages = JsonPath.read(turn1, VIDRA_PASSAGES);
        assertThat(turn1VidraPassages).isNotEmpty();

        // the page changes: Turn 1's Passage is superseded and can no longer be retrieved
        fixtureSite.page("/follow-up/vidra-szamla/", vidraSzamla("1 190 Ft"));
        ingest("followup-vidra-szamla");

        String turn2 = ask(conversationId, "És a bankkártya éves díja?");
        assertThat(JsonPath.<String>read(turn2, "$.status")).isEqualTo("ANSWERED");
        assertThat(JsonPath.<String>read(turn2, "$.answer"))
                .doesNotContain("990 Ft")
                .contains("A Vidra bankkártya éves díja 3 000 Ft.");
        List<Number> turn2VidraPassages = JsonPath.read(turn2, VIDRA_PASSAGES);
        assertThat(turn2VidraPassages).isNotEmpty().doesNotContainAnyElementsOf(turn1VidraPassages);
        assertThat(JsonPath.<List<String>>read(turn2, "$.citations[*].versionStatus")).containsOnly("CURRENT");
    }

    @Test
    void reopeningAConversationShowsAllItsTurnsInOrder() {
        long conversationId = startConversation();
        String first = "Mennyi a Hód Számla számlavezetési díja?";
        String followUp = "És mennyi a bankkártya éves díja?";
        String turn1 = ask(conversationId, first);
        String turn2 = ask(conversationId, followUp);

        chatModel.replyWith(prompt -> "A díj 999 999 Ft. [1]");
        String turn3 = ask(conversationId, "És az utalási díj?");
        assertThat(JsonPath.<String>read(turn3, "$.status")).isEqualTo("REFUSED");
        assertThat(JsonPath.<List<?>>read(turn3, "$.relevantSources")).isNotEmpty();

        api.get().uri("/conversations/{id}", conversationId)
                .header("X-Expert-Id", EXPERT)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(conversationId)
                .jsonPath("$.expertId").isEqualTo(EXPERT)
                .jsonPath("$.turns.length()").isEqualTo(3)
                .jsonPath("$.turns[0].id").isEqualTo(JsonPath.<Number>read(turn1, "$.id"))
                .jsonPath("$.turns[0].ordinal").isEqualTo(1)
                .jsonPath("$.turns[0].question").isEqualTo(first)
                .jsonPath("$.turns[0].standaloneQuestion").isEqualTo(first)
                .jsonPath("$.turns[0].productCategory").doesNotExist()
                .jsonPath("$.turns[0].status").isEqualTo("ANSWERED")
                .jsonPath("$.turns[0].answer").isEqualTo(JsonPath.<String>read(turn1, "$.answer"))
                .jsonPath("$.turns[0].citations[0].passageId").isEqualTo(JsonPath.<Number>read(turn1, "$.citations[0].passageId"))
                .jsonPath("$.turns[0].relevantSources").isEmpty()
                .jsonPath("$.turns[1].id").isEqualTo(JsonPath.<Number>read(turn2, "$.id"))
                .jsonPath("$.turns[1].ordinal").isEqualTo(2)
                .jsonPath("$.turns[1].question").isEqualTo(followUp)
                .jsonPath("$.turns[1].standaloneQuestion").isEqualTo(first + " " + followUp)
                .jsonPath("$.turns[1].status").isEqualTo("ANSWERED")
                .jsonPath("$.turns[1].answer").isEqualTo(JsonPath.<String>read(turn2, "$.answer"))
                .jsonPath("$.turns[1].citations.length()").isEqualTo(JsonPath.<List<?>>read(turn2, "$.citations").size())
                .jsonPath("$.turns[2].id").isEqualTo(JsonPath.<Number>read(turn3, "$.id"))
                .jsonPath("$.turns[2].ordinal").isEqualTo(3)
                .jsonPath("$.turns[2].status").isEqualTo("REFUSED")
                .jsonPath("$.turns[2].citations").isEmpty()
                .jsonPath("$.turns[2].relevantSources[0].sourceVersionId")
                .isEqualTo(JsonPath.<Number>read(turn3, "$.relevantSources[0].sourceVersionId"));

        long emptyConversationId = startConversation();
        api.get().uri("/conversations/{id}", emptyConversationId)
                .header("X-Expert-Id", EXPERT)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(emptyConversationId)
                .jsonPath("$.turns").isEmpty();
    }

    @Test
    void anotherExpertCannotReopenTheConversation() {
        long conversationId = startConversation();

        api.get().uri("/conversations/{id}", conversationId)
                .header("X-Expert-Id", "expert-denes")
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentType("application/problem+json");
    }

    private static String vidraSzamla(String fee) {
        return """
                <html><head><title>Vidra Számla</title></head>
                <body><main>
                  <h1>Vidra Számla</h1>
                  <h2>Díjak</h2>
                  <p>A Vidra Számla számlavezetési díja havi %s. A Vidra bankkártya éves díja 3 000 Ft.</p>
                </main></body></html>
                """.formatted(fee);
    }

    private void ingest(String sourceKey) {
        api.post().uri("/ingestion/runs?sourceKey=" + sourceKey).exchange().expectStatus().isCreated();
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
