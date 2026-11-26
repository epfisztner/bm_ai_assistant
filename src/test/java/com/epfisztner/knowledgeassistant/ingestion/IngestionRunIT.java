package com.epfisztner.knowledgeassistant.ingestion;

import com.jayway.jsonpath.JsonPath;
import com.epfisztner.knowledgeassistant.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.json.JsonCompareMode;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionRunIT extends IntegrationTest {

    @Test
    void firstIngestionOfASourceCreatesVersionOneAndReportsItInTheRunReport() {
        fixtureSite.page("/ingestion/first-version/", """
                <html><head><title>Próba Számla</title></head>
                <body><main>
                  <h1>Próba Számla</h1>
                  <p>A számlavezetési díj havi 0 Ft.</p>
                </main></body></html>
                """);

        var report = api.post().uri("/ingestion/runs?sourceKey=ingest-first-version")
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.trigger").isEqualTo("MANUAL")
                .jsonPath("$.sourceKey").isEqualTo("ingest-first-version")
                .jsonPath("$.startedAt").exists()
                .jsonPath("$.finishedAt").exists()
                .jsonPath("$.outcomes.length()").isEqualTo(1)
                .jsonPath("$.outcomes[0].sourceKey").isEqualTo("ingest-first-version")
                .jsonPath("$.outcomes[0].outcome").isEqualTo("NEW_VERSION")
                .jsonPath("$.outcomes[0].versionNumber").isEqualTo(1)
                .returnResult();
        String json = new String(report.getResponseBodyContent());
        long runId = JsonPath.<Number>read(json, "$.id").longValue();
        long sourceId = JsonPath.<Number>read(json, "$.outcomes[0].sourceId").longValue();
        long versionId = JsonPath.<Number>read(json, "$.outcomes[0].sourceVersionId").longValue();

        api.get().uri("/ingestion/runs/{id}", runId).exchange()
                .expectStatus().isOk()
                .expectBody().json(json, JsonCompareMode.STRICT);

        api.get().uri("/sources/{id}", sourceId).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.key").isEqualTo("ingest-first-version")
                .jsonPath("$.url").isEqualTo(fixtureSite.url("/ingestion/first-version/"))
                .jsonPath("$.title").isEqualTo("Próba Számla")
                .jsonPath("$.productCategory").isEqualTo("BANKSZAMLA")
                .jsonPath("$.bank").isEqualTo("Próba Bank")
                .jsonPath("$.bankProduct").isEqualTo("Próba Számla")
                .jsonPath("$.status").isEqualTo("ACTIVE")
                .jsonPath("$.currentVersion.id").isEqualTo(versionId)
                .jsonPath("$.currentVersion.versionNumber").isEqualTo(1)
                .jsonPath("$.lastFetchOutcome").isEqualTo("NEW_VERSION")
                .jsonPath("$.lastFetchAttemptAt").exists();

        api.get().uri("/sources/{id}/versions", sourceId).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].id").isEqualTo(versionId)
                .jsonPath("$[0].versionNumber").isEqualTo(1)
                .jsonPath("$[0].status").isEqualTo("CURRENT")
                .jsonPath("$[0].contentHash").value(String.class, hash -> assertThat(hash).matches("[0-9a-f]{64}"))
                .jsonPath("$[0].fetchedAt").exists();

        api.get().uri("/source-versions/{id}", versionId).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.sourceId").isEqualTo(sourceId)
                .jsonPath("$.versionNumber").isEqualTo(1)
                .jsonPath("$.status").isEqualTo("CURRENT")
                .jsonPath("$.extractedText").isEqualTo("Próba Számla\n\nA számlavezetési díj havi 0 Ft.")
                .jsonPath("$.passages.length()").isEqualTo(1)
                .jsonPath("$.passages[0].ordinal").isEqualTo(1)
                .jsonPath("$.passages[0].headingPath[0]").isEqualTo("Próba Számla")
                .jsonPath("$.passages[0].text").isEqualTo("A számlavezetési díj havi 0 Ft.");
    }

    @Test
    void anUnreachableSourceIsReportedAsFailedWithErrorDetail() {
        fixtureSite.failing("/ingestion/unreachable/", 503);

        api.post().uri("/ingestion/runs?sourceKey=ingest-unreachable")
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.outcomes[0].sourceKey").isEqualTo("ingest-unreachable")
                .jsonPath("$.outcomes[0].outcome").isEqualTo("FAILED")
                .jsonPath("$.outcomes[0].error").value(String.class, error -> assertThat(error).contains("503"))
                .jsonPath("$.outcomes[0].sourceVersionId").doesNotExist();
    }

    @Test
    void anUnknownSourceKeyIsRejectedWithProblemDetails() {
        api.post().uri("/ingestion/runs?sourceKey=no-such-source")
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentType("application/problem+json");
    }

    @Test
    void anUnknownRunIsNotFound() {
        api.get().uri("/ingestion/runs/{id}", 999_999).exchange()
                .expectStatus().isNotFound();
    }
}
