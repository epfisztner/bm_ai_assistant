package com.epfisztner.knowledgeassistant.conversation;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An Expert flags an Answer; the feedback is linked to the Turn and so to its cited Source
 * Versions. Invalid input anywhere in the API is
 * answered with RFC 9457 problem details.
 */
class FeedbackIT extends HermelinSzamlaFixture {

    @Test
    void anExpertFlagsAnAnswerAsOutdatedAndItReachesTheKnowledgeOwnerWithTheCitedSourceVersion() {
        chatModel.replyWith(prompt -> "A Hermelin Számla számlavezetési díja havi 1 357 Ft. [%d]".formatted(
                prompt.passages().stream()
                        .filter(p -> p.passage().sourceVersionId() == sourceVersionId
                                && p.passage().headingPath().contains("Díjak"))
                        .findFirst().orElseThrow().marker()));
        AskedTurn turn = askInBankszamlaConversation(QUESTION);

        api.post().uri("/turns/{id}/feedback", turn.id())
                .header("X-Expert-Id", EXPERT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("rating", "OUTDATED", "comment", "A díj januártól változott."))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.id").isNumber()
                .jsonPath("$.turnId").isEqualTo(turn.id())
                .jsonPath("$.expertId").isEqualTo(EXPERT)
                .jsonPath("$.rating").isEqualTo("OUTDATED")
                .jsonPath("$.comment").isEqualTo("A díj januártól változott.")
                .jsonPath("$.createdAt").isNotEmpty()
                .jsonPath("$.citedSourceVersions[0].sourceVersionId").isEqualTo(sourceVersionId)
                .jsonPath("$.citedSourceVersions[0].sourceId").isEqualTo(sourceId)
                .jsonPath("$.citedSourceVersions[0].url").isEqualTo(fixtureSite.url("/audit/hermelin-szamla/"));
    }

    @Test
    void knowledgeOwnerListsFeedbackAcrossExpertsNewestFirstWithTurnContextAndCitedSourceVersions() {
        chatModel.replyWith(prompt -> "A Hermelin Számla számlavezetési díja havi 1 357 Ft. [%d]".formatted(
                prompt.passages().stream()
                        .filter(p -> p.passage().sourceVersionId() == sourceVersionId
                                && p.passage().headingPath().contains("Díjak"))
                        .findFirst().orElseThrow().marker()));
        AskedTurn answeredTurn = askInBankszamlaConversation(QUESTION);
        long firstFeedbackId = submitFeedback(EXPERT, answeredTurn.id(),
                Map.of("rating", "OUTDATED", "comment", "A díj változott."));

        String secondExpert = "expert-beata";
        long mortgageConversation = startConversation(secondExpert, Map.of("productCategory", "LAKASHITEL"));
        String unanswerableQuestion = "Mekkora a lakáshitel önereje a Holdon?";
        AskedTurn refusedTurn = ask(mortgageConversation, unanswerableQuestion, secondExpert);
        long secondFeedbackId = submitFeedback(secondExpert, refusedTurn.id(), Map.of("rating", "INCORRECT"));

        api.get().uri("/feedback")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].id").isEqualTo(secondFeedbackId)
                .jsonPath("$[0].turnId").isEqualTo(refusedTurn.id())
                .jsonPath("$[0].conversationId").isEqualTo(mortgageConversation)
                .jsonPath("$[0].expertId").isEqualTo(secondExpert)
                .jsonPath("$[0].rating").isEqualTo("INCORRECT")
                .jsonPath("$[0].comment").doesNotExist()
                .jsonPath("$[0].createdAt").isNotEmpty()
                .jsonPath("$[0].question").isEqualTo(unanswerableQuestion)
                .jsonPath("$[0].productCategory").isEqualTo("LAKASHITEL")
                .jsonPath("$[0].turnStatus").isEqualTo("REFUSED")
                .jsonPath("$[0].citedSourceVersions").isEmpty()
                .jsonPath("$[1].id").isEqualTo(firstFeedbackId)
                .jsonPath("$[1].turnId").isEqualTo(answeredTurn.id())
                .jsonPath("$[1].conversationId").isEqualTo(answeredTurn.<Number>read("$.conversationId").longValue())
                .jsonPath("$[1].expertId").isEqualTo(EXPERT)
                .jsonPath("$[1].rating").isEqualTo("OUTDATED")
                .jsonPath("$[1].comment").isEqualTo("A díj változott.")
                .jsonPath("$[1].createdAt").isNotEmpty()
                .jsonPath("$[1].question").isEqualTo(QUESTION)
                .jsonPath("$[1].productCategory").isEqualTo("BANKSZAMLA")
                .jsonPath("$[1].turnStatus").isEqualTo("ANSWERED")
                .jsonPath("$[1].citedSourceVersions[0].sourceId").isEqualTo(sourceId)
                .jsonPath("$[1].citedSourceVersions[0].sourceVersionId").isEqualTo(sourceVersionId)
                .jsonPath("$[1].citedSourceVersions[0].versionNumber").isEqualTo(1)
                .jsonPath("$[1].citedSourceVersions[0].url").isEqualTo(fixtureSite.url("/audit/hermelin-szamla/"));
    }

    @Test
    void knowledgeOwnerFiltersFeedbackByRatingAndProductCategory() {
        long bankszamlaOutdatedId = submitFeedback(EXPERT, askInBankszamlaConversation(QUESTION).id(),
                Map.of("rating", "OUTDATED"));
        long mortgageConversation = startConversation("expert-csilla", Map.of("productCategory", "LAKASHITEL"));
        long lakashitelIncorrectId = submitFeedback("expert-csilla",
                ask(mortgageConversation, "Mekkora a lakáshitel önereje?", "expert-csilla").id(),
                Map.of("rating", "INCORRECT"));
        long bankszamlaHelpfulId = submitFeedback(EXPERT, askInBankszamlaConversation(QUESTION).id(),
                Map.of("rating", "HELPFUL"));

        String byRating = getFeedbackJson("/feedback?rating=INCORRECT");
        assertThat(JsonPath.<List<String>>read(byRating, "$[*].rating")).containsOnly("INCORRECT");
        assertThat(readIds(byRating)).contains(lakashitelIncorrectId)
                .doesNotContain(bankszamlaOutdatedId, bankszamlaHelpfulId);

        String byCategory = getFeedbackJson("/feedback?productCategory=LAKASHITEL");
        assertThat(JsonPath.<List<String>>read(byCategory, "$[*].productCategory")).containsOnly("LAKASHITEL");
        assertThat(readIds(byCategory)).contains(lakashitelIncorrectId)
                .doesNotContain(bankszamlaOutdatedId, bankszamlaHelpfulId);

        String byBoth = getFeedbackJson("/feedback?rating=OUTDATED&productCategory=BANKSZAMLA");
        assertThat(JsonPath.<List<String>>read(byBoth, "$[*].rating")).containsOnly("OUTDATED");
        assertThat(JsonPath.<List<String>>read(byBoth, "$[*].productCategory")).containsOnly("BANKSZAMLA");
        assertThat(readIds(byBoth)).contains(bankszamlaOutdatedId)
                .doesNotContain(lakashitelIncorrectId, bankszamlaHelpfulId);
    }

    @Test
    void knowledgeOwnerLimitsTheNumberOfReturnedFeedbackItems() {
        submitFeedback(EXPERT, askInBankszamlaConversation(QUESTION).id(), Map.of("rating", "HELPFUL"));
        long newestId = submitFeedback(EXPERT, askInBankszamlaConversation(QUESTION).id(),
                Map.of("rating", "OUTDATED"));

        api.get().uri("/feedback?limit=1")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].id").isEqualTo(newestId);
    }

    @Test
    void invalidFeedbackQueueFiltersAreRejectedWithProblemDetails() {
        api.get().uri("/feedback?rating=GREAT")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.instance").isEqualTo("/api/v1/feedback")
                .jsonPath("$.errors[0].field").isEqualTo("rating")
                .jsonPath("$.errors[0].message").value(String.class, message -> assertThat(message)
                        .contains("GREAT").contains("HELPFUL").contains("INCORRECT").contains("OUTDATED"));

        api.get().uri("/feedback?productCategory=UNKNOWN")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.instance").isEqualTo("/api/v1/feedback")
                .jsonPath("$.errors[0].field").isEqualTo("productCategory")
                .jsonPath("$.errors[0].message").value(String.class, message -> assertThat(message)
                        .contains("UNKNOWN").contains("LAKASHITEL").contains("SZEMELYI_KOLCSON").contains("BANKSZAMLA"));

        for (String invalidLimit : List.of("0", "201", "abc")) {
            api.get().uri("/feedback?limit=" + invalidLimit)
                    .exchange()
                    .expectStatus().isBadRequest()
                    .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .expectBody()
                    .jsonPath("$.status").isEqualTo(400)
                    .jsonPath("$.instance").isEqualTo("/api/v1/feedback")
                    .jsonPath("$.errors[0].field").isEqualTo("limit");
        }
    }

    @Test
    void theCommentIsOptional() {
        AskedTurn turn = askInBankszamlaConversation(QUESTION);

        api.post().uri("/turns/{id}/feedback", turn.id())
                .header("X-Expert-Id", EXPERT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("rating", "HELPFUL"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.rating").isEqualTo("HELPFUL")
                .jsonPath("$.comment").doesNotExist();
    }

    @Test
    void anUnknownRatingIsRejectedWithProblemDetails() {
        AskedTurn turn = askInBankszamlaConversation(QUESTION);

        api.post().uri("/turns/{id}/feedback", turn.id())
                .header("X-Expert-Id", EXPERT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("rating", "GREAT"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.title").isEqualTo("Bad Request")
                .jsonPath("$.instance").isEqualTo("/api/v1/turns/" + turn.id() + "/feedback")
                .jsonPath("$.errors[0].field").isEqualTo("rating")
                .jsonPath("$.errors[0].message").value(String.class, message -> assertThat(message)
                        .contains("GREAT").contains("HELPFUL").contains("INCORRECT").contains("OUTDATED"));
    }

    @Test
    void aMissingRatingIsRejectedWithProblemDetails() {
        AskedTurn turn = askInBankszamlaConversation(QUESTION);

        api.post().uri("/turns/{id}/feedback", turn.id())
                .header("X-Expert-Id", EXPERT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("comment", "nincs értékelés"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.errors[0].field").isEqualTo("rating");
    }

    @Test
    void onlyTheExpertWhoAskedCanGiveFeedback() {
        AskedTurn turn = askInBankszamlaConversation(QUESTION);

        api.post().uri("/turns/{id}/feedback", turn.id())
                .header("X-Expert-Id", "expert-intruder")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("rating", "INCORRECT"))
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON);
        api.post().uri("/turns/{id}/feedback", turn.id())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("rating", "INCORRECT"))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void aBlankQuestionIsRejectedWithProblemDetailsNamingTheField() {
        long conversationId = startConversation(Map.of());

        api.post().uri("/conversations/{id}/turns", conversationId)
                .header("X-Expert-Id", EXPERT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("question", " "))
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.errors[0].field").isEqualTo("question");
    }

    @Test
    void aNonNumericIdIsRejectedWithProblemDetails() {
        api.get().uri("/turns/abc/audit").header("X-Expert-Id", EXPERT).exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(400);
    }

    private long submitFeedback(String expertId, long turnId, Map<String, ?> body) {
        String response = new String(api.post().uri("/turns/{id}/feedback", turnId)
                .header("X-Expert-Id", expertId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange()
                .expectStatus().isCreated()
                .expectBody().returnResult().getResponseBodyContent());
        return JsonPath.<Number>read(response, "$.id").longValue();
    }

    private String getFeedbackJson(String uri) {
        return new String(api.get().uri(uri)
                .exchange()
                .expectStatus().isOk()
                .expectBody().returnResult().getResponseBodyContent());
    }

    private static List<Long> readIds(String json) {
        return JsonPath.<List<Number>>read(json, "$[*].id").stream().map(Number::longValue).toList();
    }
}
