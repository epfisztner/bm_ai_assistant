package com.epfisztner.knowledgeassistant.retrieval;

import com.jayway.jsonpath.JsonPath;
import com.epfisztner.knowledgeassistant.support.FixtureSite;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.util.Map;

/** The fixture Source and Questions shared by the retrieval tests, plus the HTTP calls they make. */
final class CsiripeloSzamla {

    static final String SOURCE_KEY = "retrieval-csiripelo-szamla";
    static final String EXPERT = "expert-retrieval";

    /** Shares no word (after stemming) with the Source, only meaning: "pénzt kivenni az ATM-ből" ~ "készpénzfelvétel". */
    static final String PARAPHRASED_QUESTION = "Kerül valamibe pénzt kivenni az ATM-ből?";
    /** Uses the Source's own words. */
    static final String KEYWORD_QUESTION = "Díjmentes a készpénzfelvétel a Csiripelő Számlával?";
    static final String FEE_SENTENCE = "A Csiripelő Számlához tartozó kártyával a készpénzfelvétel díjmentes.";

    private CsiripeloSzamla() {
    }

    /** Serves the page and ingests it; returns the run report. */
    static String ingest(FixtureSite site, RestTestClient api) {
        site.page("/retrieval/csiripelo-szamla/", """
                <html><head><title>Csiripelő Számla</title></head>
                <body><main>
                  <h1>Csiripelő Számla</h1>
                  <h2>Díjak</h2>
                  <p>%s</p>
                  <h2>Nyitás</h2>
                  <p>A Csiripelő Számla online nyitható.</p>
                </main></body></html>
                """.formatted(FEE_SENTENCE));
        return runIngestion(api);
    }

    static String runIngestion(RestTestClient api) {
        return new String(api.post().uri("/ingestion/runs?sourceKey=" + SOURCE_KEY).exchange()
                .expectStatus().isCreated()
                .expectBody().returnResult().getResponseBodyContent());
    }

    static String ask(RestTestClient api, String question) {
        String conversation = new String(api.post().uri("/conversations")
                .header("X-Expert-Id", EXPERT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of())
                .exchange()
                .expectStatus().isCreated()
                .expectBody().returnResult().getResponseBodyContent());
        long conversationId = JsonPath.<Number>read(conversation, "$.id").longValue();
        return new String(api.post().uri("/conversations/{id}/turns", conversationId)
                .header("X-Expert-Id", EXPERT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("question", question))
                .exchange()
                .expectStatus().isCreated()
                .expectBody().returnResult().getResponseBodyContent());
    }

    static String version(RestTestClient api, long sourceVersionId) {
        return new String(api.get().uri("/source-versions/{id}", sourceVersionId).exchange()
                .expectStatus().isOk()
                .expectBody().returnResult().getResponseBodyContent());
    }
}
