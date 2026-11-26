package com.epfisztner.knowledgeassistant.ingestion;

import com.epfisztner.knowledgeassistant.ingestion.IngestionRuns.Trigger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Optional;

/**
 * Re-ingests all registered Sources on the cron expression {@code knowledge.ingestion.schedule.cron}
 * (Spring cron, six fields). Disabled unless that property is set to something other than {@code -}.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnExpression("'${knowledge.ingestion.schedule.cron:-}' != '-' && '${knowledge.ingestion.schedule.cron:-}' != ''")
class ScheduledIngestion {

    private final Ingestion ingestion;

    ScheduledIngestion(Ingestion ingestion) {
        this.ingestion = ingestion;
    }

    @Scheduled(cron = "${knowledge.ingestion.schedule.cron}")
    void run() {
        ingestion.run(Trigger.SCHEDULE, Optional.empty());
    }
}
