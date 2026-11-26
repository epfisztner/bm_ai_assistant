package com.epfisztner.knowledgeassistant.ingestion;

import com.jayway.jsonpath.JsonPath;
import com.epfisztner.knowledgeassistant.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A Source's content changes over time: re-ingestion keeps every distinct content as a Source
 * Version, only the Current Version is searched, and earlier Citations stay traceable.
 */
class SourceVersionsIT extends IntegrationTest {

    private static final String EXPERT = "expert-versions";
    private static final String PATH = "/versions/szarka-szamla/";
    private static final String KEY = "versions-szarka-szamla";
    private static final String QUESTION = "Mennyi a Szarka Számla számlavezetési díja?";

    private static String page(String fee) {
        return """
                <html><head><title>Szarka Számla</title></head>
                <body><main>
                  <h1>Szarka Számla</h1>
                  <h2>Díjak</h2>
                  <p>A Szarka Számla számlavezetési díja havi %s Ft.</p>
                </main></body></html>
                """.formatted(fee);
    }

    @Test
    void aSourceKeepsItsHistoryWhileOnlyTheCurrentVersionIsSearched() {
        fixtureSite.page(PATH, page("4 321"));
        String first = ingest();
        assertThat(JsonPath.<String>read(first, "$.outcomes[0].outcome")).isEqualTo("NEW_VERSION");
        long sourceId = JsonPath.<Number>read(first, "$.outcomes[0].sourceId").longValue();
        long v1 = JsonPath.<Number>read(first, "$.outcomes[0].sourceVersionId").longValue();

        long conversationId = startConversation();
        String earlyTurn = ask(conversationId);
        assertThat(JsonPath.<Number>read(earlyTurn, "$.citations[0].sourceVersionId").longValue()).isEqualTo(v1);
        String citedText = JsonPath.read(earlyTurn, "$.citations[0].passageText");
        assertThat(citedText).contains("havi 4 321 Ft");

        // unchanged content: no new version
        String unchanged = ingest();
        assertThat(JsonPath.<String>read(unchanged, "$.outcomes[0].outcome")).isEqualTo("UNCHANGED");
        assertThat(JsonPath.<Number>read(unchanged, "$.outcomes[0].sourceVersionId").longValue()).isEqualTo(v1);
        assertThat(JsonPath.<Integer>read(unchanged, "$.outcomes[0].versionNumber")).isEqualTo(1);

        // changed content: version 2 becomes current, version 1 is superseded
        fixtureSite.page(PATH, page("5 678"));
        String changed = ingest();
        assertThat(JsonPath.<String>read(changed, "$.outcomes[0].outcome")).isEqualTo("NEW_VERSION");
        assertThat(JsonPath.<Integer>read(changed, "$.outcomes[0].versionNumber")).isEqualTo(2);
        long v2 = JsonPath.<Number>read(changed, "$.outcomes[0].sourceVersionId").longValue();

        api.get().uri("/sources/{id}/versions", sourceId).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(2)
                .jsonPath("$[0].id").isEqualTo(v2)
                .jsonPath("$[0].status").isEqualTo("CURRENT")
                .jsonPath("$[0].supersededAt").doesNotExist()
                .jsonPath("$[1].id").isEqualTo(v1)
                .jsonPath("$[1].status").isEqualTo("SUPERSEDED")
                .jsonPath("$[1].supersededAt").exists();
        api.get().uri("/source-versions/{id}", v1).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.passages[0].text").isEqualTo("A Szarka Számla számlavezetési díja havi 4 321 Ft.");

        // retrieval switched to the new content
        String laterTurn = ask(conversationId);
        assertThat(JsonPath.<Number>read(laterTurn, "$.citations[0].sourceVersionId").longValue()).isEqualTo(v2);
        assertThat(JsonPath.<String>read(laterTurn, "$.answer")).contains("havi 5 678 Ft").doesNotContain("4 321 Ft");
        assertThat(JsonPath.<List<Number>>read(laterTurn, "$.citations[*].sourceVersionId"))
                .extracting(Number::longValue).doesNotContain(v1);

        // the earlier Citation is flagged as based on superseded content, its text unchanged
        long earlyTurnId = JsonPath.<Number>read(earlyTurn, "$.id").longValue();
        api.get().uri("/conversations/{id}", conversationId)
                .header("X-Expert-Id", EXPERT)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(conversationId)
                .jsonPath("$.turns.length()").isEqualTo(2)
                .jsonPath("$.turns[0].id").isEqualTo(earlyTurnId)
                .jsonPath("$.turns[0].citations[0].sourceVersionId").isEqualTo(v1)
                .jsonPath("$.turns[0].citations[0].versionStatus").isEqualTo("SUPERSEDED")
                .jsonPath("$.turns[0].citations[0].currentVersionId").isEqualTo(v2)
                .jsonPath("$.turns[0].citations[0].passageText").isEqualTo(citedText)
                .jsonPath("$.turns[1].citations[0].versionStatus").isEqualTo("CURRENT")
                .jsonPath("$.turns[1].citations[0].currentVersionId").doesNotExist();

        // a failed fetch leaves the Current Version untouched
        fixtureSite.failing(PATH, 500);
        String failed = ingest();
        assertThat(JsonPath.<String>read(failed, "$.outcomes[0].outcome")).isEqualTo("FAILED");
        assertThat(JsonPath.<String>read(failed, "$.outcomes[0].error")).contains("500");
        api.get().uri("/sources/{id}", sourceId).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.currentVersion.id").isEqualTo(v2)
                .jsonPath("$.lastFetchOutcome").isEqualTo("FAILED");
        assertThat(JsonPath.<Number>read(ask(conversationId), "$.citations[0].sourceVersionId").longValue()).isEqualTo(v2);
    }

    @Test
    void anotherExpertCannotReadTheConversation() {
        long conversationId = startConversation();
        api.get().uri("/conversations/{id}", conversationId)
                .header("X-Expert-Id", "someone-else")
                .exchange()
                .expectStatus().isNotFound();
    }

    private String ingest() {
        return new String(api.post().uri("/ingestion/runs?sourceKey=" + KEY).exchange()
                .expectStatus().isCreated()
                .expectBody().returnResult().getResponseBodyContent());
    }

    private String ask(long conversationId) {
        String turn = new String(api.post().uri("/conversations/{id}/turns", conversationId)
                .header("X-Expert-Id", EXPERT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("question", QUESTION))
                .exchange()
                .expectStatus().isCreated()
                .expectBody().returnResult().getResponseBodyContent());
        assertThat(JsonPath.<String>read(turn, "$.status")).isEqualTo("ANSWERED");
        return turn;
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
