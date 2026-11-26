package com.epfisztner.knowledgeassistant.conversation;

import com.epfisztner.knowledgeassistant.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationListingIT extends IntegrationTest {

    @Test
    void listingRequiresTheExpertIdHeader() {
        api.get().uri("/conversations")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.instance").isEqualTo("/api/v1/conversations");
    }

    @Test
    void anExpertListsOnlyTheirOwnConversationsOrderedByLatestActivity() {
        String alice = "expert-list-alice";
        String bob = "expert-list-bob";

        String firstBody = startConversation(alice, Map.of("productCategory", "LAKASHITEL"));
        long firstId = JsonPath.<Number>read(firstBody, "$.id").longValue();
        OffsetDateTime firstCreatedAt = OffsetDateTime.parse(JsonPath.read(firstBody, "$.createdAt"));

        String emptyBody = startConversation(alice, Map.of());
        long emptyId = JsonPath.<Number>read(emptyBody, "$.id").longValue();
        OffsetDateTime emptyCreatedAt = OffsetDateTime.parse(JsonPath.read(emptyBody, "$.createdAt"));

        String bobsBody = startConversation(bob, Map.of("productCategory", "BANKSZAMLA"));
        long bobsId = JsonPath.<Number>read(bobsBody, "$.id").longValue();
        ask(bob, bobsId, "Bob kérdése");

        ask(alice, firstId, "Első kérdés a lakáshitelről");
        String secondTurn = ask(alice, firstId, "Második kérdés a futamidőről");
        OffsetDateTime secondTurnCreatedAt = OffsetDateTime.parse(JsonPath.read(secondTurn, "$.createdAt"));

        String listBody = new String(api.get().uri("/conversations")
                .header("X-Expert-Id", alice)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(2)
                .jsonPath("$[0].id").isEqualTo(firstId)
                .jsonPath("$[0].productCategory").isEqualTo("LAKASHITEL")
                .jsonPath("$[0].turnCount").isEqualTo(2)
                .jsonPath("$[0].lastQuestion").isEqualTo("Második kérdés a futamidőről")
                .jsonPath("$[0].turns").doesNotExist()
                .jsonPath("$[1].id").isEqualTo(emptyId)
                .jsonPath("$[1].productCategory").doesNotExist()
                .jsonPath("$[1].turnCount").isEqualTo(0)
                .jsonPath("$[1].lastQuestion").doesNotExist()
                .jsonPath("$[1].turns").doesNotExist()
                .returnResult().getResponseBodyContent());

        assertThat(OffsetDateTime.parse(JsonPath.read(listBody, "$[0].createdAt"))).isEqualTo(firstCreatedAt);
        assertThat(OffsetDateTime.parse(JsonPath.read(listBody, "$[0].lastActivityAt"))).isEqualTo(secondTurnCreatedAt);
        assertThat(OffsetDateTime.parse(JsonPath.read(listBody, "$[1].createdAt"))).isEqualTo(emptyCreatedAt);
        assertThat(OffsetDateTime.parse(JsonPath.read(listBody, "$[1].lastActivityAt"))).isEqualTo(emptyCreatedAt);

        Map<String, Object> emptySummary = JsonPath.read(listBody, "$[1]");
        assertThat(emptySummary).containsKey("lastQuestion").containsEntry("lastQuestion", null);
    }

    @Test
    void limitCapsTheNumberOfConversationsReturned() {
        String expert = "expert-list-limit";
        startConversation(expert, Map.of());
        long second = JsonPath.<Number>read(startConversation(expert, Map.of()), "$.id").longValue();
        long third = JsonPath.<Number>read(startConversation(expert, Map.of()), "$.id").longValue();

        api.get().uri("/conversations?limit=2")
                .header("X-Expert-Id", expert)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(2)
                .jsonPath("$[0].id").isEqualTo(third)
                .jsonPath("$[1].id").isEqualTo(second);
    }

    @Test
    void invalidLimitIsRejectedWithProblemDetails() {
        for (String invalid : List.of("0", "-1", "201", "abc")) {
            api.get().uri("/conversations?limit={limit}", invalid)
                    .header("X-Expert-Id", "expert-list-invalid")
                    .exchange()
                    .expectStatus().isBadRequest()
                    .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .expectBody()
                    .jsonPath("$.status").isEqualTo(400)
                    .jsonPath("$.instance").isEqualTo("/api/v1/conversations")
                    .jsonPath("$.errors[0].field").isEqualTo("limit");
        }
    }

    @Test
    void openApiDocumentsConversationListing() {
        server.get().uri("/v3/api-docs")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.paths['/api/v1/conversations'].get.summary").isNotEmpty();
    }

    private String startConversation(String expertId, Map<String, ?> body) {
        return new String(api.post().uri("/conversations")
                .header("X-Expert-Id", expertId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange()
                .expectStatus().isCreated()
                .expectBody().returnResult().getResponseBodyContent());
    }

    private String ask(String expertId, long conversationId, String question) {
        return new String(api.post().uri("/conversations/{id}/turns", conversationId)
                .header("X-Expert-Id", expertId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("question", question))
                .exchange()
                .expectStatus().isCreated()
                .expectBody().returnResult().getResponseBodyContent());
    }
}
