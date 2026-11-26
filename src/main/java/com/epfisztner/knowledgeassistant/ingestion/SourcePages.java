package com.epfisztner.knowledgeassistant.ingestion;

import com.epfisztner.knowledgeassistant.ingestion.SnapshotProperties.Mode;
import com.epfisztner.knowledgeassistant.source.SourceRegistry.RegisteredSource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/**
 * Loads a Source's raw page: from the network ({@link PageFetcher}), also writing it to the
 * snapshot directory in {@code record} mode, or only from the snapshot directory in {@code replay} mode.
 */
@Component
class SourcePages {

    private final PageFetcher fetcher;
    private final SnapshotProperties snapshots;

    SourcePages(PageFetcher fetcher, SnapshotProperties snapshots) {
        this.fetcher = fetcher;
        this.snapshots = snapshots;
    }

    String load(RegisteredSource source) {
        if (snapshots.mode() == Mode.REPLAY) {
            return readSnapshot(source);
        }
        String raw = fetcher.fetch(source.url());
        if (snapshots.mode() == Mode.RECORD) {
            writeSnapshot(source, raw);
        }
        return raw;
    }

    private String readSnapshot(RegisteredSource source) {
        Path file = snapshotFile(source);
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            throw new IllegalStateException("No snapshot for Source " + source.key() + " at " + file);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read snapshot " + file, e);
        }
    }

    private void writeSnapshot(RegisteredSource source, String raw) {
        Path file = snapshotFile(source);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, raw, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write snapshot " + file, e);
        }
    }

    private Path snapshotFile(RegisteredSource source) {
        String fileName = source.key().replaceAll("[^A-Za-z0-9._-]", "_") + ".html";
        return snapshots.directory().toAbsolutePath().resolve(fileName);
    }
}
