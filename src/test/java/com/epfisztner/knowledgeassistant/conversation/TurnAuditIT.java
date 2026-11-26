package com.epfisztner.knowledgeassistant.conversation;

import com.epfisztner.knowledgeassistant.generation.GroundedPrompt;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient.BodyContentSpec;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A Compliance Officer can reconstruct how any Turn was produced from {@code GET /turns/{id}/audit},
 * for Answers and for every kind of Refusal.
 */
class TurnAuditIT extends HermelinSzamlaFixture {

    @Test
    void theAuditOfAnAnswerHoldsEverythingNeededToReconstructIt() {
        AskedTurn turn = askInBankszamlaConversation(QUESTION);
        String answer = turn.read("$.answer");
        long citedPassage = turn.<Number>read("$.citations[0].passageId").longValue();
        String cited = "$.retrievedPassages[?(@.passageId == " + citedPassage + ")]";

        audit(turn.id())
                .jsonPath("$.turnId").isEqualTo(turn.id())
                .jsonPath("$.question").isEqualTo(QUESTION)
                .jsonPath("$.standaloneQuestion").isEqualTo(QUESTION)
                .jsonPath("$.productCategory").isEqualTo("BANKSZAMLA")
                .jsonPath("$.status").isEqualTo("ANSWERED")
                .jsonPath("$.refusalReason").doesNotExist()
                .jsonPath("$.answer").isEqualTo(answer)
                .jsonPath("$.chatProvider").isEqualTo("offline")
                .jsonPath("$.chatModel").isEqualTo("extractive-v1")
                .jsonPath("$.embeddingModel").doesNotExist()
                .jsonPath("$.promptVersion").isEqualTo("answer-v3")
                .jsonPath("$.rawModelOutput").isEqualTo(answer)
                .jsonPath("$.verdict.passed").isEqualTo(true)
                .jsonPath("$.verdict.failedChecks").isEmpty()
                .jsonPath("$.rewrite").doesNotExist()
                .jsonPath("$.latencyMs").value(Number.class, latency -> assertThat(latency.longValue()).isNotNegative())
                .jsonPath("$.citations[0].passageId").isEqualTo(citedPassage)
                .jsonPath("$.retrievedPassages[0].lexicalRank").isEqualTo(1)
                .jsonPath(cited + ".sourceId").value(List.class, ids -> assertThat(longs(ids)).containsExactly(sourceId))
                .jsonPath(cited + ".sourceVersionId").value(List.class, ids -> assertThat(longs(ids))
                        .containsExactly(sourceVersionId))
                .jsonPath(cited + ".inPrompt").isEqualTo(List.of(true))
                .jsonPath(cited + ".marker").isEqualTo(List.of(turn.<Number>read("$.citations[0].marker").intValue()))
                .jsonPath(cited + ".lexicalRank").value(List.class, ranks -> assertThat(ranks).doesNotContainNull())
                .jsonPath(cited + ".vectorRank").value(List.class, ranks -> assertThat(ranks).containsOnlyNulls())
                .jsonPath(cited + ".score").value(List.class, scores -> assertThat(((Number) scores.getFirst()).doubleValue())
                        .isPositive());
    }

    @Test
    void aFollowUpIsRewrittenByTheModelAndTheAuditKeepsTheRewriteStep() {
        AskedTurn first = askInBankszamlaConversation(QUESTION);
        chatModel.rewriteWith("  Hogyan nyitható a Hermelin Számla?\nMert a kérdés a nyitásra vonatkozik.");

        AskedTurn followUp = askFollowUp(first, "És hogyan nyitható?");

        assertThat(followUp.<String>read("$.standaloneQuestion")).isEqualTo("Hogyan nyitható a Hermelin Számla?");
        audit(followUp.id())
                .jsonPath("$.standaloneQuestion").isEqualTo("Hogyan nyitható a Hermelin Számla?")
                .jsonPath("$.rewrite.promptVersion").isEqualTo("rewrite-v1")
                .jsonPath("$.rewrite.rawOutput")
                .isEqualTo("  Hogyan nyitható a Hermelin Számla?\nMert a kérdés a nyitásra vonatkozik.")
                .jsonPath("$.rewrite.chatProvider").isEqualTo("offline")
                .jsonPath("$.rewrite.chatModel").isEqualTo("scripted")
                .jsonPath("$.rewrite.fellBackToJoin").isEqualTo(false);
    }

    @Test
    void aBlankRewriteFallsBackToJoiningThePreviousStandaloneQuestionAndTheAuditSaysSo() {
        AskedTurn first = askInBankszamlaConversation(QUESTION);
        chatModel.rewriteWith("   ");

        AskedTurn followUp = askFollowUp(first, "És hogyan nyitható?");

        assertThat(followUp.<String>read("$.standaloneQuestion")).isEqualTo(QUESTION + " És hogyan nyitható?");
        audit(followUp.id())
                .jsonPath("$.rewrite.promptVersion").isEqualTo("rewrite-v1")
                .jsonPath("$.rewrite.rawOutput").isEqualTo("   ")
                .jsonPath("$.rewrite.fellBackToJoin").isEqualTo(true);
    }

    @Test
    void theOfflineAdapterRewritesByJoiningAndReportsItself() {
        AskedTurn first = askInBankszamlaConversation(QUESTION);

        AskedTurn followUp = askFollowUp(first, "És hogyan nyitható?");

        assertThat(followUp.<String>read("$.standaloneQuestion")).isEqualTo(QUESTION + " És hogyan nyitható?");
        audit(followUp.id())
                .jsonPath("$.rewrite.promptVersion").isEqualTo("rewrite-v1")
                .jsonPath("$.rewrite.rawOutput").isEqualTo(QUESTION + " És hogyan nyitható?")
                .jsonPath("$.rewrite.chatProvider").isEqualTo("offline")
                .jsonPath("$.rewrite.chatModel").isEqualTo("join-v1")
                .jsonPath("$.rewrite.fellBackToJoin").isEqualTo(false);
    }

    @Test
    void theAuditOfAVerificationFailedRefusalKeepsTheRawOutputAndTheFailedChecks() {
        chatModel.replyWith(prompt -> "A Hermelin Számla díja havi 9 876 Ft. [%d]".formatted(marker(prompt, "Díjak")));
        AskedTurn turn = askInBankszamlaConversation(QUESTION);

        audit(turn.id())
                .jsonPath("$.status").isEqualTo("REFUSED")
                .jsonPath("$.refusalReason").isEqualTo("VERIFICATION_FAILED")
                .jsonPath("$.chatProvider").isEqualTo("offline")
                .jsonPath("$.chatModel").isEqualTo("scripted")
                .jsonPath("$.promptVersion").isEqualTo("answer-v3")
                .jsonPath("$.rawModelOutput").value(String.class, raw -> assertThat(raw).contains("9 876 Ft"))
                .jsonPath("$.answer").value(String.class, shown -> assertThat(shown).doesNotContain("9 876"))
                .jsonPath("$.verdict.passed").isEqualTo(false)
                .jsonPath("$.verdict.failedChecks[0]").value(String.class, check -> assertThat(check).contains("9 876"))
                .jsonPath("$.citations").isEmpty()
                .jsonPath("$.retrievedPassages[?(@.sourceVersionId == " + sourceVersionId + ")].inPrompt")
                .value(List.class, inPrompt -> assertThat(inPrompt).contains(true));
    }

    @Test
    void theAuditOfAModelDeclinedRefusalKeepsTheRawOutputWithoutAVerdict() {
        chatModel.replyWith("NINCS_ELEG_BIZONYITEK");
        AskedTurn turn = askInBankszamlaConversation(QUESTION);

        audit(turn.id())
                .jsonPath("$.refusalReason").isEqualTo("MODEL_DECLINED")
                .jsonPath("$.rawModelOutput").isEqualTo("NINCS_ELEG_BIZONYITEK")
                .jsonPath("$.retrievedPassages").isNotEmpty()
                .jsonPath("$.verdict").doesNotExist();
    }

    @Test
    void theAuditOfANoEvidenceRefusalShowsTheModelWasNotCalled() {
        AskedTurn turn = askInBankszamlaConversation("Zümmögő pántlikagiliszta xilofonozik?");

        audit(turn.id())
                .jsonPath("$.status").isEqualTo("REFUSED")
                .jsonPath("$.refusalReason").isEqualTo("NO_EVIDENCE")
                .jsonPath("$.retrievedPassages").isEmpty()
                .jsonPath("$.chatProvider").isEqualTo("offline")
                .jsonPath("$.chatModel").isEqualTo("extractive-v1")
                .jsonPath("$.promptVersion").doesNotExist()
                .jsonPath("$.rawModelOutput").doesNotExist()
                .jsonPath("$.verdict").doesNotExist();
    }

    @Test
    void onlyTheExpertWhoAskedCanSeeTheAudit() {
        AskedTurn turn = askInBankszamlaConversation(QUESTION);

        api.get().uri("/turns/{id}/audit", turn.id()).header("X-Expert-Id", "expert-intruder").exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON);
        api.get().uri("/turns/{id}/audit", turn.id()).exchange()
                .expectStatus().isUnauthorized();
    }

    private BodyContentSpec audit(long turnId) {
        return api.get().uri("/turns/{id}/audit", turnId).header("X-Expert-Id", EXPERT).exchange()
                .expectStatus().isOk()
                .expectBody();
    }

    private static List<Long> longs(List<?> numbers) {
        return numbers.stream().map(n -> ((Number) n).longValue()).toList();
    }

    private static int marker(GroundedPrompt prompt, String heading) {
        return prompt.passages().stream()
                .filter(p -> p.passage().source().bankProduct().equals("Hermelin Számla") && p.passage().headingPath().contains(heading))
                .findFirst().orElseThrow().marker();
    }
}
