package com.epfisztner.knowledgeassistant.ingestion;

import com.epfisztner.knowledgeassistant.ingestion.IngestionRuns.Trigger;
import com.epfisztner.knowledgeassistant.source.KnowledgeBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Seeds an empty Knowledge Base on startup. Application runners complete before Spring Boot
 * publishes readiness, so the service reports ready only after the seeding run.
 */
@Component
@ConditionalOnBooleanProperty("knowledge.ingestion.seed-on-startup")
class StartupSeeding implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupSeeding.class);

    private final KnowledgeBase knowledgeBase;
    private final Ingestion ingestion;

    StartupSeeding(KnowledgeBase knowledgeBase, Ingestion ingestion) {
        this.knowledgeBase = knowledgeBase;
        this.ingestion = ingestion;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!knowledgeBase.isEmpty()) {
            log.info("Knowledge Base already contains Source Versions; skipping startup seeding");
            return;
        }
        log.info("Knowledge Base is empty; seeding it from the Source Registry");
        ingestion.run(Trigger.STARTUP, Optional.empty());
    }
}
