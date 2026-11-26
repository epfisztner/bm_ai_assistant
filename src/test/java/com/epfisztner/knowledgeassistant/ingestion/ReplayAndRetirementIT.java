package com.epfisztner.knowledgeassistant.ingestion;

import com.jayway.jsonpath.JsonPath;
import com.epfisztner.knowledgeassistant.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Its own application context (and database): ingestion replays pages from a snapshot directory
 * instead of the network, and reads a Source Registry file the test rewrites between runs.
 */
class ReplayAndRetirementIT extends IntegrationTest {

    private static final Path WORK = Path.of("target", "replay-and-retirement-it").toAbsolutePath();
    private static final Path SNAPSHOTS = WORK.resolve("snapshots");
    private static final Path REGISTRY = WORK.resolve("registry.yaml");

    @DynamicPropertySource
    static void replayFromSnapshots(DynamicPropertyRegistry registry) {
        registry.add("knowledge.ingestion.snapshots.mode", () -> "replay");
        registry.add("knowledge.ingestion.snapshots.directory", SNAPSHOTS::toString);
        registry.add("knowledge.source-registry.location", () -> REGISTRY.toUri().toString());
    }

    @BeforeEach
    void cleanWorkDirectory() throws IOException {
        if (Files.exists(WORK)) {
            try (Stream<Path> files = Files.walk(WORK)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(file);
                }
            }
        }
        Files.createDirectories(SNAPSHOTS);
    }

    @Test
    void replayModeIngestsSavedSnapshotsInsteadOfTheNetwork() throws IOException {
        fixtureSite.failing("/replay/tukor-szamla/", 503);
        writeRegistry("replay-tukor-szamla");
        snapshot("replay-tukor-szamla", "Tükör Számla", "A Tükör Számla havi díja 333 Ft.");

        String report = run();
        assertThat(JsonPath.<String>read(report, "$.outcomes[0].outcome")).isEqualTo("NEW_VERSION");
        long versionId = JsonPath.<Number>read(report, "$.outcomes[0].sourceVersionId").longValue();

        api.get().uri("/source-versions/{id}", versionId).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.passages[0].text").isEqualTo("A Tükör Számla havi díja 333 Ft.");
    }

    @Test
    void aSourceWithoutASnapshotFailsInReplayMode() {
        writeRegistry("replay-missing");

        String report = run();
        assertThat(JsonPath.<String>read(report, "$.outcomes[0].outcome")).isEqualTo("FAILED");
        assertThat(JsonPath.<String>read(report, "$.outcomes[0].error")).contains("snapshot");
    }

    @Test
    void aSourceDroppedFromTheRegistryIsRetiredExcludedFromRetrievalAndKeepsItsHistory() throws IOException {
        writeRegistry("retire-kept", "retire-dropped");
        snapshot("retire-kept", "Maradó Számla", "A Maradó Számla havi díja 100 Ft.");
        snapshot("retire-dropped", "Kivezetett Számla", "A Kivezetett Számla havi díja 700 Ft.");
        String first = run();
        assertThat(outcomeOf(first, "retire-kept")).isEqualTo("NEW_VERSION");
        assertThat(outcomeOf(first, "retire-dropped")).isEqualTo("NEW_VERSION");
        long droppedId = JsonPath.<List<Number>>read(first, "$.outcomes[?(@.sourceKey == 'retire-dropped')].sourceId").getFirst().longValue();
        long droppedVersion = JsonPath.<List<Number>>read(first, "$.outcomes[?(@.sourceKey == 'retire-dropped')].sourceVersionId").getFirst().longValue();
        String question = "Mennyi a Kivezetett Számla havi díja?";
        String beforeRetirement = ask(question);
        long conversationBefore = JsonPath.<Number>read(beforeRetirement, "$.conversationId").longValue();
        assertThat(JsonPath.<Number>read(beforeRetirement, "$.citations[0].sourceId").longValue()).isEqualTo(droppedId);
        assertThat(JsonPath.<String>read(beforeRetirement, "$.citations[0].sourceStatus")).isEqualTo("ACTIVE");

        writeRegistry("retire-kept");
        String second = run();
        assertThat(outcomeOf(second, "retire-kept")).isEqualTo("UNCHANGED");
        assertThat(outcomeOf(second, "retire-dropped")).isEqualTo("RETIRED");
        assertThat(JsonPath.<List<Number>>read(second, "$.outcomes[?(@.sourceKey == 'retire-dropped')].sourceId").getFirst().longValue())
                .isEqualTo(droppedId);

        api.get().uri("/sources/{id}", droppedId).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("RETIRED")
                .jsonPath("$.currentVersion.id").isEqualTo(droppedVersion);
        api.get().uri("/source-versions/{id}", droppedVersion).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.passages[0].text").isEqualTo("A Kivezetett Számla havi díja 700 Ft.");

        // the earlier Citation keeps its (still current) version and now signals the retired Source
        api.get().uri("/conversations/{id}", conversationBefore).header("X-Expert-Id", "expert-replay").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.turns[0].citations[0].sourceVersionId").isEqualTo(droppedVersion)
                .jsonPath("$.turns[0].citations[0].versionStatus").isEqualTo("CURRENT")
                .jsonPath("$.turns[0].citations[0].sourceStatus").isEqualTo("RETIRED");

        String afterRetirement = ask(question);
        assertThat(JsonPath.<List<Number>>read(afterRetirement, "$.citations[*].sourceId"))
                .extracting(Number::longValue).doesNotContain(droppedId);
        assertThat(JsonPath.<String>read(afterRetirement, "$.answer")).doesNotContain("700 Ft");

        // already retired: not reported again
        assertThat(JsonPath.<List<String>>read(run(), "$.outcomes[*].sourceKey")).doesNotContain("retire-dropped");
    }

    /** Other tests in this context share the database, so a full run may report their Sources too. */
    private static String outcomeOf(String report, String key) {
        List<String> outcomes = JsonPath.read(report, "$.outcomes[?(@.sourceKey == '" + key + "')].outcome");
        assertThat(outcomes).as("outcomes for %s", key).hasSize(1);
        return outcomes.getFirst();
    }

    private void writeRegistry(String... keys) {
        var yaml = new StringBuilder("sources:\n");
        for (String key : keys) {
            yaml.append("""
                      - key: %s
                        url: %s
                        productCategory: BANKSZAMLA
                        bank: Visszajátszó Bank
                        bankProduct: %s
                    """.formatted(key, fixtureSite.url("/replay/" + key + "/"), key));
        }
        try {
            Files.writeString(REGISTRY, yaml, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A snapshot as written in record mode: {@code <source key>.html} holding the raw page. */
    private static void snapshot(String key, String title, String text) throws IOException {
        Files.writeString(SNAPSHOTS.resolve(key + ".html"),
                "<html><body><main><h1>%s</h1><p>%s</p></main></body></html>".formatted(title, text),
                StandardCharsets.UTF_8);
    }

    private String run() {
        return new String(api.post().uri("/ingestion/runs").exchange()
                .expectStatus().isCreated()
                .expectBody().returnResult().getResponseBodyContent());
    }

    private String ask(String question) {
        String conversation = new String(api.post().uri("/conversations")
                .header("X-Expert-Id", "expert-replay")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of())
                .exchange()
                .expectStatus().isCreated()
                .expectBody().returnResult().getResponseBodyContent());
        long id = JsonPath.<Number>read(conversation, "$.id").longValue();
        return new String(api.post().uri("/conversations/{id}/turns", id)
                .header("X-Expert-Id", "expert-replay")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("question", question))
                .exchange()
                .expectStatus().isCreated()
                .expectBody().returnResult().getResponseBodyContent());
    }
}
