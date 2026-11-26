package com.epfisztner.knowledgeassistant.ingestion;

import com.epfisztner.knowledgeassistant.embedding.PassageEmbeddings;
import com.epfisztner.knowledgeassistant.ingestion.ContentExtractor.ExtractedPage;
import com.epfisztner.knowledgeassistant.ingestion.IngestionRuns.IngestionRun;
import com.epfisztner.knowledgeassistant.ingestion.IngestionRuns.SourceOutcome;
import com.epfisztner.knowledgeassistant.ingestion.IngestionRuns.Trigger;
import com.epfisztner.knowledgeassistant.source.IngestionOutcome;
import com.epfisztner.knowledgeassistant.source.KnowledgeBase;
import com.epfisztner.knowledgeassistant.source.KnowledgeBase.NewPassage;
import com.epfisztner.knowledgeassistant.source.KnowledgeBase.NewVersion;
import com.epfisztner.knowledgeassistant.source.KnowledgeBase.RetiredSource;
import com.epfisztner.knowledgeassistant.source.KnowledgeBase.VersionRef;
import com.epfisztner.knowledgeassistant.source.SourceRegistry;
import com.epfisztner.knowledgeassistant.source.SourceRegistry.RegisteredSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Runs ingestion: fetches the registered Sources one after another, extracts their content and
 * stores a new Source Version when the normalized extracted text changed. Each run is recorded
 * with a per-Source outcome. A failed fetch or extraction leaves the Current Version untouched; a
 * full run retires ACTIVE Sources that are no longer in the Source Registry.
 */
@Service
class Ingestion {

    static class UnknownSourceException extends RuntimeException {
        UnknownSourceException(String key) {
            super("No Source with key '" + key + "' in the Source Registry");
        }
    }

    private static final Logger log = LoggerFactory.getLogger(Ingestion.class);

    private final SourceRegistry registry;
    private final KnowledgeBase knowledgeBase;
    private final SourcePages pages;
    private final ContentExtractor extractor;
    private final IngestionRuns runs;
    private final PassageEmbeddings embeddings;

    Ingestion(SourceRegistry registry, KnowledgeBase knowledgeBase, SourcePages pages, ContentExtractor extractor,
              IngestionRuns runs, PassageEmbeddings embeddings) {
        this.registry = registry;
        this.knowledgeBase = knowledgeBase;
        this.pages = pages;
        this.extractor = extractor;
        this.runs = runs;
        this.embeddings = embeddings;
    }

    /**
     * Ingests all registered Sources, or only the one with {@code sourceKey}, and returns the run report.
     */
    synchronized IngestionRun run(Trigger trigger, Optional<String> sourceKey) {
        List<RegisteredSource> sources = sourceKey
                .map(key -> List.of(registry.find(key).orElseThrow(() -> new UnknownSourceException(key))))
                .orElseGet(registry::sources);

        long runId = runs.start(trigger, sourceKey.orElse(null), now());
        log.info("Ingestion run {} ({}) started for {} Source(s)", runId, trigger, sources.size());
        var outcomes = new ArrayList<SourceOutcome>();
        for (RegisteredSource source : sources) {
            SourceOutcome outcome = ingest(source);
            log.info("Ingestion run {}: {} -> {}{}", runId, source.key(), outcome.outcome(),
                    outcome.error() == null ? "" : " (" + outcome.error() + ")");
            outcomes.add(outcome);
        }
        if (sourceKey.isEmpty()) {
            // A full run is the registry's verdict on which Sources exist; one-Source runs retire nothing.
            List<String> registered = sources.stream().map(RegisteredSource::key).toList();
            for (RetiredSource retired : knowledgeBase.retireAllExcept(registered)) {
                log.info("Ingestion run {}: {} -> RETIRED (no longer in the Source Registry)", runId, retired.key());
                outcomes.add(new SourceOutcome(retired.key(), retired.id(), IngestionOutcome.RETIRED,
                        retired.currentVersionId(), null, null));
            }
        }
        // New Passages, and all Passages after an embedding model change, get embedded.
        embeddings.refresh();
        runs.finish(runId, outcomes, now());
        return runs.find(runId).orElseThrow();
    }

    private SourceOutcome ingest(RegisteredSource source) {
        long sourceId = knowledgeBase.register(source);
        OffsetDateTime fetchedAt = now();
        try {
            String raw = pages.load(source);
            ExtractedPage page = extractor.extract(raw, source.url());
            if (page.passages().isEmpty()) {
                throw new IllegalStateException("No content could be extracted from " + source.url());
            }
            String hash = contentHash(page.text());
            Optional<VersionRef> current = knowledgeBase.currentVersion(sourceId);
            if (current.isPresent() && current.get().contentHash().equals(hash)) {
                knowledgeBase.recordFetch(sourceId, fetchedAt, IngestionOutcome.UNCHANGED, null);
                return new SourceOutcome(source.key(), sourceId, IngestionOutcome.UNCHANGED, current.get().id(),
                        current.get().versionNumber(), null);
            }
            List<NewPassage> passages = page.passages().stream()
                    .map(p -> new NewPassage(p.ordinal(), p.headingPath(), p.text()))
                    .toList();
            VersionRef version = knowledgeBase.addVersion(sourceId,
                    new NewVersion(hash, raw, page.title(), page.text(), fetchedAt, passages));
            knowledgeBase.recordFetch(sourceId, fetchedAt, IngestionOutcome.NEW_VERSION, null);
            return new SourceOutcome(source.key(), sourceId, IngestionOutcome.NEW_VERSION, version.id(),
                    version.versionNumber(), null);
        } catch (RuntimeException e) {
            String error = e.getClass().getSimpleName() + ": " + e.getMessage();
            if (error.length() > 1000) {
                error = error.substring(0, 1000) + "…";
            }
            knowledgeBase.recordFetch(sourceId, fetchedAt, IngestionOutcome.FAILED, error);
            return new SourceOutcome(source.key(), sourceId, IngestionOutcome.FAILED, null, null, error);
        }
    }

    /**
     * SHA-256 over the extracted text with whitespace normalized, so markup-only changes don't create versions.
     */
    static String contentHash(String extractedText) {
        String normalized = extractedText.replaceAll("\\s+", " ").strip();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
