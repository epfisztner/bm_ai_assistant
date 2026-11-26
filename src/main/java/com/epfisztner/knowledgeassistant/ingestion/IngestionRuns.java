package com.epfisztner.knowledgeassistant.ingestion;

import com.epfisztner.knowledgeassistant.jdbc.SqlEnums;
import com.epfisztner.knowledgeassistant.source.IngestionOutcome;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Persists ingestion run reports.
 */
@Repository
class IngestionRuns {

    enum Trigger {STARTUP, MANUAL, SCHEDULE}

    record SourceOutcome(String sourceKey, Long sourceId, IngestionOutcome outcome, Long sourceVersionId,
                         Integer versionNumber, String error) {
    }

    record IngestionRun(long id, Trigger trigger, String sourceKey, OffsetDateTime startedAt,
                        OffsetDateTime finishedAt, List<SourceOutcome> outcomes) {
    }

    private final JdbcClient jdbc;

    IngestionRuns(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    long start(Trigger trigger, String sourceKey, OffsetDateTime startedAt) {
        return jdbc.sql("INSERT INTO ingestion_run (trigger, source_key, started_at) VALUES (:trigger, :key, :at) RETURNING id")
                .param("trigger", SqlEnums.name(trigger))
                .param("key", sourceKey)
                .param("at", startedAt)
                .query(Long.class)
                .single();
    }

    @Transactional
    void finish(long runId, List<SourceOutcome> outcomes, OffsetDateTime finishedAt) {
        for (int i = 0; i < outcomes.size(); i++) {
            SourceOutcome outcome = outcomes.get(i);
            jdbc.sql("""
                            INSERT INTO ingestion_run_outcome (run_id, ordinal, source_key, source_id, outcome, source_version_id, error)
                            VALUES (:runId, :ordinal, :key, :sourceId, :outcome, :versionId, :error)
                            """)
                    .param("runId", runId)
                    .param("ordinal", i + 1)
                    .param("key", outcome.sourceKey())
                    .param("sourceId", outcome.sourceId())
                    .param("outcome", SqlEnums.name(outcome.outcome()))
                    .param("versionId", outcome.sourceVersionId())
                    .param("error", outcome.error())
                    .update();
        }
        jdbc.sql("UPDATE ingestion_run SET finished_at = :at WHERE id = :id")
                .param("id", runId)
                .param("at", finishedAt)
                .update();
    }

    Optional<IngestionRun> find(long id) {
        return jdbc.sql("SELECT id, trigger, source_key, started_at, finished_at FROM ingestion_run WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> new IngestionRun(rs.getLong("id"), Trigger.valueOf(rs.getString("trigger")),
                        rs.getString("source_key"), rs.getObject("started_at", OffsetDateTime.class),
                        rs.getObject("finished_at", OffsetDateTime.class), outcomes(id)))
                .optional();
    }

    private List<SourceOutcome> outcomes(long runId) {
        return jdbc.sql("""
                        SELECT o.source_key, o.source_id, o.outcome, o.source_version_id, v.version_number, o.error
                        FROM ingestion_run_outcome o
                        LEFT JOIN source_version v ON v.id = o.source_version_id
                        WHERE o.run_id = :runId ORDER BY o.ordinal
                        """)
                .param("runId", runId)
                .query(SourceOutcome.class)
                .list();
    }
}
