package com.epfisztner.knowledgeassistant.provider;

import com.jayway.jsonpath.JsonPath;
import com.epfisztner.knowledgeassistant.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The offline (extractive) chat adapter answering through the API: it quotes the best-matching
 * sentences of the retrieved Passages, each line of a list on its own, and keeps Bank Products
 * with similar names apart.
 */
class OfflineAnswersIT extends IntegrationTest {

    private static final String EXPERT = "expert-offline";
    private static boolean ingested;

    @BeforeEach
    void ingestFixturesOnce() {
        if (ingested) {
            return;
        }
        page("offline-pele-szamla", "/offline/pele-szamla/", "Pele Számla", """
                <h2>Az ingyenesség feltételei</h2>
                <ul>
                  <li>Bankmonitoron keresztüli igénylés</li>
                  <li>Havonta beérkezik a számládra 432 100 Ft jóváírás</li>
                  <li>Nem rendelkezel Pele számlával</li>
                </ul>
                """);
        // the Nyest ECO page also names Nyest ECO Plusz, so both Sources match a question about either
        page("offline-nyest-eco", "/offline/nyest-eco/", "Nyest ECO bankszámla", """
                <p>Kalkulálj, hogy a 2 évig ingyenes Nyest ECO bankszámlánál van-e jobb!</p>
                <h2>Kondíciók</h2>
                <p>A Nyest ECO, Nyest ECO Plusz bankszámla kondíciós listák tartalmazzák.</p>
                """);
        page("offline-nyest-eco-plusz", "/offline/nyest-eco-plusz/", "Nyest ECO Plusz bankszámla", """
                <p>Kalkulálj, hogy az 5 évig ingyenes Nyest ECO Plusz bankszámlánál van-e jobb!</p>
                """);
        page("offline-vakond-plusz", "/offline/vakond-plusz/", "Vakond Plusz bankszámla", """
                <p>A részletes feltételeket a Vakond, Vakond Plusz bankszámla kondíciós listák tartalmazzák.</p>
                <p>Mert 5 évig ingyenes a Vakond számlavezetés.</p>
                """);
        page("offline-sun-kolcson", "/offline/sun-kolcson/", "Sün Kölcsön", """
                <p>A Sün Kölcsön THM-je 13,7%. A minimális hitelösszeg 321 000 Ft.</p>
                """);
        ingested = true;
    }

    @Test
    void quotesTheMatchingLineOfAListOnItsOwn() {
        String turn = ask("Mekkora havi jóváírás kell a Pele Számlához?");

        assertThat(JsonPath.<String>read(turn, "$.status")).isEqualTo("ANSWERED");
        assertThat(JsonPath.<String>read(turn, "$.answer"))
                .matches("Havonta beérkezik a számládra 432 100 Ft jóváírás \\[\\d+]");
        assertThat(JsonPath.<String>read(turn, "$.citations[0].bankProduct")).isEqualTo("Pele Számla");
    }

    @Test
    void answersFromTheBankProductTheQuestionNamesMostSpecifically() {
        String aboutPlusz = ask("Hány évig ingyenes a Nyest ECO Plusz bankszámla?");
        String aboutEco = ask("Hány évig ingyenes a Nyest ECO bankszámla?");

        assertThat(JsonPath.<String>read(aboutPlusz, "$.answer"))
                .matches("Kalkulálj, hogy az 5 évig ingyenes Nyest ECO Plusz bankszámlánál van-e jobb! \\[\\d+]");
        assertThat(JsonPath.<List<String>>read(aboutPlusz, "$.citations[*].bankProduct"))
                .containsOnly("Nyest ECO Plusz");
        assertThat(JsonPath.<String>read(aboutEco, "$.answer"))
                .matches("Kalkulálj, hogy a 2 évig ingyenes Nyest ECO bankszámlánál van-e jobb! \\[\\d+]");
        assertThat(JsonPath.<List<String>>read(aboutEco, "$.citations[*].bankProduct")).containsOnly("Nyest ECO");
    }

    @Test
    void doesNotPreferSentencesThatMerelyRepeatTheNamedBankProduct() {
        String turn = ask("Hány évig ingyenes a Vakond Plusz bankszámla?");

        assertThat(JsonPath.<String>read(turn, "$.answer")).matches("Mert 5 évig ingyenes a Vakond számlavezetés\\. \\[\\d+]");
    }

    @Test
    void answersEveryPartOfAQuestionAboutANamedBankProduct() {
        String turn = ask("Mekkora a Sün Kölcsön THM-je és a minimális hitelösszeg?");

        assertThat(JsonPath.<String>read(turn, "$.answer"))
                .matches("A Sün Kölcsön THM-je 13,7%\\. \\[(\\d+)]\n\nA minimális hitelösszeg 321 000 Ft\\. \\[\\1]");
    }

    private void page(String key, String path, String title, String body) {
        fixtureSite.page(path, """
                <html><head><title>%s</title></head>
                <body><main>
                  <h1>%s</h1>
                  %s
                </main></body></html>
                """.formatted(title, title, body));
        api.post().uri("/ingestion/runs?sourceKey=" + key).exchange().expectStatus().isCreated();
    }

    private String ask(String question) {
        String conversation = new String(api.post().uri("/conversations")
                .header("X-Expert-Id", EXPERT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of())
                .exchange()
                .expectStatus().isCreated()
                .expectBody().returnResult().getResponseBodyContent(), StandardCharsets.UTF_8);
        long id = JsonPath.<Number>read(conversation, "$.id").longValue();
        return new String(api.post().uri("/conversations/{id}/turns", id)
                .header("X-Expert-Id", EXPERT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("question", question))
                .exchange()
                .expectStatus().isCreated()
                .expectBody().returnResult().getResponseBodyContent(), StandardCharsets.UTF_8);
    }
}
