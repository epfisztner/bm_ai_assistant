package com.epfisztner.knowledgeassistant.embedding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Keeps Passage embeddings in step with the configured embedding model: embeds every Passage of a
 * Current Version whose embedding model id differs from the configured one (new Passages have
 * none). Runs after each ingestion run and on startup, so a model change re-embeds the Knowledge
 * Base. Without an embedding model it does nothing. A failing provider is logged and leaves the
 * remaining Passages for the next refresh; retrieval then uses keyword search for them.
 */
@Service
public class PassageEmbeddings {

    private static final Logger log = LoggerFactory.getLogger(PassageEmbeddings.class);
    private static final int BATCH = 32;

    private record PendingPassage(long id, String content) {
    }

    private final JdbcClient jdbc;
    private final Optional<EmbeddingModelPort> model;

    PassageEmbeddings(JdbcClient jdbc, Optional<EmbeddingModelPort> model) {
        this.jdbc = jdbc;
        this.model = model;
    }

    /**
     * Embeds all Passages of Current Versions not yet embedded with the configured model; returns how many.
     */
    public synchronized int refresh() {
        if (model.isEmpty()) {
            return 0;
        }
        EmbeddingModelPort embeddingModel = model.get();
        String modelId = embeddingModel.modelId();
        int embedded = 0;
        try {
            List<PendingPassage> batch;
            while (!(batch = pending(modelId)).isEmpty()) {
                List<float[]> vectors = embeddingModel.embed(batch.stream().map(PendingPassage::content).toList());
                if (vectors.size() != batch.size()) {
                    throw new IllegalStateException("Embedding model returned " + vectors.size()
                            + " vectors for " + batch.size() + " texts");
                }
                for (int i = 0; i < batch.size(); i++) {
                    jdbc.sql("UPDATE passage SET embedding = :embedding::vector, embedding_model = :model WHERE id = :id")
                            .param("embedding", vectorLiteral(vectors.get(i)))
                            .param("model", modelId)
                            .param("id", batch.get(i).id())
                            .update();
                }
                embedded += batch.size();
            }
        } catch (RuntimeException e) {
            log.warn("Embedding Passages with model {} failed after {} Passage(s); retrying on the next refresh",
                    modelId, embedded, e);
        }
        if (embedded > 0) {
            log.info("Embedded {} Passage(s) with model {}", embedded, modelId);
        }
        return embedded;
    }

    /**
     * The text a Passage is embedded from: the same labels its keyword search vector covers.
     */
    private List<PendingPassage> pending(String modelId) {
        return jdbc.sql("""
                        SELECT p.id, concat_ws(' ', coalesce(s.title_override, v.title), s.bank, s.bank_product,
                                               array_to_string(p.heading_path, ' '), p.text) AS content
                        FROM passage p
                        JOIN source_version v ON v.id = p.source_version_id AND v.status = 'CURRENT'
                        JOIN source s ON s.id = v.source_id
                        WHERE p.embedding_model IS DISTINCT FROM :model
                        ORDER BY p.id
                        LIMIT :batch
                        """)
                .param("model", modelId)
                .param("batch", BATCH)
                .query(PendingPassage.class)
                .list();
    }

    /**
     * pgvector text form, e.g. {@code [0.1,0.2]}.
     */
    public static String vectorLiteral(float[] vector) {
        StringBuilder literal = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                literal.append(',');
            }
            literal.append(vector[i]);
        }
        return literal.append(']').toString();
    }
}
