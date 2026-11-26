package com.epfisztner.knowledgeassistant.ingestion;

import com.epfisztner.knowledgeassistant.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** In snapshot mode (the test profile's default) every fetched raw page is written to the snapshot directory. */
class SnapshotRecordingIT extends IntegrationTest {

    @Value("${knowledge.ingestion.snapshots.directory}")
    Path snapshotDirectory;

    @Test
    void eachFetchedPageIsSavedUnderItsSourceKey() throws IOException {
        String html = "<html><body><main><h1>Fénykép Számla</h1><p>Havi díj 0 Ft, kártyadíj 1 200 Ft.</p></main></body></html>";
        fixtureSite.page("/snapshots/recorded/", html);

        api.post().uri("/ingestion/runs?sourceKey=snapshot-recorded").exchange()
                .expectStatus().isCreated()
                .expectBody().jsonPath("$.outcomes[0].outcome").isEqualTo("NEW_VERSION");

        Path snapshot = snapshotDirectory.resolve("snapshot-recorded.html");
        assertThat(snapshot).exists();
        assertThat(Files.readString(snapshot, StandardCharsets.UTF_8)).isEqualTo(html);
    }
}
