package com.epfisztner.knowledgeassistant.embedding;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Re-embeds Passages on startup when the configured embedding model changed (before readiness).
 */
@Component
class StartupEmbeddingRefresh implements ApplicationRunner {

    private final PassageEmbeddings embeddings;

    StartupEmbeddingRefresh(PassageEmbeddings embeddings) {
        this.embeddings = embeddings;
    }

    @Override
    public void run(ApplicationArguments args) {
        embeddings.refresh();
    }
}
