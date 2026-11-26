package com.epfisztner.knowledgeassistant.embedding;

import java.util.List;

/**
 * An embedding model (provider adapter seam). There is at most one bean; with embedding provider
 * {@code none} there is none and retrieval is keyword-only.
 */
public interface EmbeddingModelPort {

    /**
     * Identifies the model and anything that changes its vectors (e.g. dimensions). Stored per
     * Passage; vectors are only ever compared with vectors of the same model id.
     */
    String modelId();

    /**
     * One vector per text, in order.
     */
    List<float[]> embed(List<String> texts);
}
