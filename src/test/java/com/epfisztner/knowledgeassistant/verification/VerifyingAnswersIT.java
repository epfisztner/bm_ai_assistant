package com.epfisztner.knowledgeassistant.verification;

import com.jayway.jsonpath.JsonPath;
import com.epfisztner.knowledgeassistant.generation.GroundedPrompt;
import com.epfisztner.knowledgeassistant.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient.BodyContentSpec;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nothing reaches the Expert unless it passes the verifier (ADR-0002). The chat model output is
 * scripted per test; markers are looked up in the prompt by the Passage's heading.
 */
class VerifyingAnswersIT extends IntegrationTest {

    private static final String EXPERT = "expert-vera";
    private static final String QUESTION = "Mennyi a Borz Kölcsön THM-je és futamideje?";

    private static long sourceId;

    /** The fixture Source among the Refusal's relevant Sources (the shared test KB may rank others higher). */
    private static String borzSource;

    @BeforeEach
    void ingestBorzKolcsonOnce() {
        if (sourceId != 0) {
            return;
        }
        fixtureSite.page("/verification/borz-kolcson/", """
                <html><head><title>Borz Kölcsön</title></head>
                <body><main>
                  <h1>Borz Kölcsön</h1>
                  <h2>Költségek</h2>
                  <p>A Borz Kölcsön THM-je 12,5%. A minimális hitelösszeg 300 000 Ft.</p>
                  <h2>Futamidő</h2>
                  <p>A Borz Kölcsön futamideje legfeljebb 84 hónap.</p>
                  <h2>Jövedelem és keret</h2>
                  <p>A Borz Kölcsön elvárt jövedelme 225 ezer Ft, a hitelkeret 1 500 000 Ft, az éves keretösszeg 2,5 milliárd Ft.</p>
                  <h2>Akciós feltételek</h2>
                  <p>A Borz Kölcsön akciós díja 225 000 Ft, a kiemelt hitelösszeg 1,5 millió Ft, a csoportos keret 2 000 000 000 Ft.</p>
                  <h2>Pontszám</h2>
                  <p>A Borz Kölcsön ügyfélpont-határa 225 ezer, a prémium pontszám 450 000.</p>
                </main></body></html>
                """);
        String report = new String(api.post().uri("/ingestion/runs?sourceKey=verify-borz-kolcson").exchange()
                .expectStatus().isCreated()
                .expectBody().returnResult().getResponseBodyContent());
        sourceId = JsonPath.<Number>read(report, "$.outcomes[0].sourceId").longValue();
        borzSource = "$.relevantSources[?(@.sourceId == " + sourceId + ")]";
    }

    @Test
    void theDeclineSentinelIsAModelDeclinedRefusalListingTheRelevantSources() {
        chatModel.replyWith("NINCS_ELEG_BIZONYITEK");

        ask(QUESTION)
                .jsonPath("$.status").isEqualTo("REFUSED")
                .jsonPath("$.refusalReason").isEqualTo("MODEL_DECLINED")
                .jsonPath("$.answer").value(String.class, answer -> assertThat(answer)
                        .contains("nem tartalmaz elegendő").doesNotContain("NINCS_ELEG_BIZONYITEK"))
                .jsonPath("$.citations").isEmpty()
                .jsonPath(borzSource + ".sourceTitle").isEqualTo(List.of("Borz Kölcsön"))
                .jsonPath(borzSource + ".bank").isEqualTo(List.of("Patak Bank"))
                .jsonPath(borzSource + ".bankProduct").isEqualTo(List.of("Borz Kölcsön"))
                .jsonPath(borzSource + ".url").isEqualTo(List.of(fixtureSite.url("/verification/borz-kolcson/")));
    }

    @Test
    void aValidMultiParagraphAnswerWithAListIsReleasedWithItsCitations() {
        chatModel.replyWith(prompt -> {
            int costs = marker(prompt, "Költségek");
            int term = marker(prompt, "Futamidő");
            return """
                    A Patak Bank Borz Kölcsönének főbb adatai: [%1$d]
                    - THM: 12,5 %% [%1$d]
                    - minimális hitelösszeg: 300000 Ft [%1$d]

                    A futamidő legfeljebb 84 hónap. [%2$d]
                    """.formatted(costs, term);
        });

        ask(QUESTION)
                .jsonPath("$.status").isEqualTo("ANSWERED")
                .jsonPath("$.refusalReason").doesNotExist()
                .jsonPath("$.answer").value(String.class, answer -> assertThat(answer)
                        .contains("THM: 12,5 %").contains("legfeljebb 84 hónap."))
                .jsonPath("$.citations.length()").isEqualTo(2)
                .jsonPath("$.citations[0].passageHeadingPath[1]").isEqualTo("Költségek")
                .jsonPath("$.citations[1].passageHeadingPath[1]").isEqualTo("Futamidő")
                .jsonPath("$.relevantSources").isEmpty();
    }

    @Test
    void aFabricatedNumberIsAVerificationFailedRefusalThatNeverShowsTheModelOutput() {
        chatModel.replyWith(prompt -> "A Borz Kölcsön THM-je 9,9%%. [%d]".formatted(marker(prompt, "Költségek")));

        assertVerificationFailed(ask(QUESTION), "9,9");
    }

    @Test
    void aFigureFromAPassageTheParagraphDoesNotCiteIsAVerificationFailedRefusal() {
        chatModel.replyWith(prompt -> "A futamidő legfeljebb 84 hónap. [%d]".formatted(marker(prompt, "Költségek")));

        assertVerificationFailed(ask(QUESTION), "84 hónap");
    }

    @Test
    void aMarkerOutsideThePresentedPassagesIsAVerificationFailedRefusal() {
        chatModel.replyWith("A Borz Kölcsön THM-je 12,5%. [99]");

        assertVerificationFailed(ask(QUESTION), "[99]");
    }

    @Test
    void anUncitedParagraphIsAVerificationFailedRefusal() {
        chatModel.replyWith(prompt -> """
                A Borz Kölcsön THM-je 12,5%%. [%d]

                A kölcsön gyorsan és egyszerűen igényelhető.
                """.formatted(marker(prompt, "Költségek")));

        assertVerificationFailed(ask(QUESTION), "egyszerűen igényelhető");
    }

    @Test
    void anUncitedLeadInBeforeACitedListIsAVerificationFailedRefusal() {
        chatModel.replyWith(prompt -> """
                A Borz Kölcsön a legjobb választás:
                - THM: 12,5 %% [%1$d]
                """.formatted(marker(prompt, "Költségek")));

        assertVerificationFailed(ask(QUESTION), "a legjobb választás");
    }

    @Test
    void anUncitedHeadingIsAVerificationFailedRefusal() {
        chatModel.replyWith(prompt -> """
                ## Ajánlott kölcsön

                A Borz Kölcsön THM-je 12,5%%. [%d]
                """.formatted(marker(prompt, "Költségek")));

        assertVerificationFailed(ask(QUESTION), "Ajánlott kölcsön");
    }

    @Test
    void theOfflineAdapterAlwaysPassesVerification() {
        ask("Mennyi a Borz Kölcsön futamideje?")
                .jsonPath("$.status").isEqualTo("ANSWERED")
                .jsonPath("$.answer").value(String.class, answer -> assertThat(answer).contains("84 hónap"));
        ask("Mekkora a Borz Kölcsön THM-je és a minimális hitelösszeg?")
                .jsonPath("$.status").isEqualTo("ANSWERED")
                .jsonPath("$.answer").value(String.class, answer -> assertThat(answer).contains("12,5%"));
    }

    @Test
    void hungarianThousandScaleWordAndFullDigitsMatchInBothDirections() {
        chatModel.replyWith(prompt -> "A Borz Kölcsön elvárt jövedelme 225 000 Ft. [%d]"
                .formatted(marker(prompt, "Jövedelem és keret")));

        ask("Mennyi a Borz Kölcsön elvárt jövedelme?")
                .jsonPath("$.status").isEqualTo("ANSWERED")
                .jsonPath("$.refusalReason").doesNotExist()
                .jsonPath("$.answer").value(String.class, answer -> assertThat(answer).contains("225 000 Ft"));

        chatModel.replyWith(prompt -> "A Borz Kölcsön akciós díja 225 ezer Ft. [%d]"
                .formatted(marker(prompt, "Akciós feltételek")));

        ask("Mennyi a Borz Kölcsön akciós díja?")
                .jsonPath("$.status").isEqualTo("ANSWERED")
                .jsonPath("$.refusalReason").doesNotExist()
                .jsonPath("$.answer").value(String.class, answer -> assertThat(answer).contains("225 ezer Ft"));
    }

    @Test
    void decimalMillionAndBillionScaleFiguresMatchFullDigitsInBothDirections() {
        chatModel.replyWith(prompt -> "A Borz Kölcsön hitelkerete 1,5 millió Ft, az éves keretösszeg 2 500 000 000 Ft. [%d]"
                .formatted(marker(prompt, "Jövedelem és keret")));

        ask("Mennyi a Borz Kölcsön hitelkerete és éves keretösszege?")
                .jsonPath("$.status").isEqualTo("ANSWERED")
                .jsonPath("$.refusalReason").doesNotExist()
                .jsonPath("$.answer").value(String.class, answer -> assertThat(answer)
                        .contains("1,5 millió Ft").contains("2 500 000 000 Ft"));

        chatModel.replyWith(prompt -> "A Borz Kölcsön kiemelt hitelösszege 1 500 000 Ft, a csoportos keret 2 milliárd Ft. [%d]"
                .formatted(marker(prompt, "Akciós feltételek")));

        ask("Mennyi a Borz Kölcsön kiemelt hitelösszege és csoportos kerete?")
                .jsonPath("$.status").isEqualTo("ANSWERED")
                .jsonPath("$.refusalReason").doesNotExist()
                .jsonPath("$.answer").value(String.class, answer -> assertThat(answer)
                        .contains("1 500 000 Ft").contains("2 milliárd Ft"));
    }

    @Test
    void aDroppedScaleMultiplierIsAVerificationFailedRefusal() {
        chatModel.replyWith(prompt -> "A Borz Kölcsön elvárt jövedelme 225 Ft. [%d]"
                .formatted(marker(prompt, "Jövedelem és keret")));

        assertVerificationFailed(ask("Mennyi a Borz Kölcsön elvárt jövedelme?"), "225 Ft");
    }

    @Test
    void aMismatchedUnitAfterAScaleWordIsAVerificationFailedRefusal() {
        chatModel.replyWith(prompt -> "A Borz Kölcsön elvárt jövedelme 225 ezer év. [%d]"
                .formatted(marker(prompt, "Jövedelem és keret")));

        assertVerificationFailed(ask("Mennyi a Borz Kölcsön elvárt jövedelme?"), "225 ezer év");
    }

    @Test
    void unitlessScaleFiguresNormalizeInBothDirectionsAndRejectInventedUnits() {
        chatModel.replyWith(prompt -> "A Borz Kölcsön ügyfélpont-határa 225 000, a prémium pontszám 450 ezer. [%d]"
                .formatted(marker(prompt, "Pontszám")));

        ask("Mennyi a Borz Kölcsön ügyfélpont-határa és prémium pontszáma?")
                .jsonPath("$.status").isEqualTo("ANSWERED")
                .jsonPath("$.refusalReason").doesNotExist()
                .jsonPath("$.answer").value(String.class, answer -> assertThat(answer)
                        .contains("225 000").contains("450 ezer"));

        chatModel.replyWith(prompt -> "A Borz Kölcsön ügyfélpont-határa 225 000 Ft. [%d]"
                .formatted(marker(prompt, "Pontszám")));

        assertVerificationFailed(ask("Mennyi a Borz Kölcsön ügyfélpont-határa?"), "225 000 Ft");
    }

    private void assertVerificationFailed(BodyContentSpec turn, String modelOutputFragment) {
        turn.jsonPath("$.status").isEqualTo("REFUSED")
                .jsonPath("$.refusalReason").isEqualTo("VERIFICATION_FAILED")
                .jsonPath("$.answer").value(String.class, answer -> assertThat(answer)
                        .contains("nem sikerült a forrásokkal ellenőrizni").doesNotContain(modelOutputFragment))
                .jsonPath("$.citations").isEmpty()
                .jsonPath(borzSource + ".bankProduct").isEqualTo(List.of("Borz Kölcsön"));
    }

    private BodyContentSpec ask(String question) {
        return api.post().uri("/conversations/{id}/turns", startConversation())
                .header("X-Expert-Id", EXPERT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("question", question))
                .exchange()
                .expectStatus().isCreated()
                .expectBody();
    }

    private static int marker(GroundedPrompt prompt, String heading) {
        return prompt.passages().stream()
                .filter(p -> p.passage().source().bankProduct().equals("Borz Kölcsön") && p.passage().headingPath().contains(heading))
                .findFirst().orElseThrow().marker();
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
