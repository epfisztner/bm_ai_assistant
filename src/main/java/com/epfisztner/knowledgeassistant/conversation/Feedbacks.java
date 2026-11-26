package com.epfisztner.knowledgeassistant.conversation;

import com.epfisztner.knowledgeassistant.conversation.Conversations.TurnStatus;
import com.epfisztner.knowledgeassistant.jdbc.SqlArrays;
import com.epfisztner.knowledgeassistant.jdbc.SqlEnums;
import com.epfisztner.knowledgeassistant.productcategory.ProductCategory;
import com.epfisztner.knowledgeassistant.source.SourceDetails;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Stores and reads Expert feedback on Turns (hand-written SQL, ADR-0005). Feedback is linked to
 * the Turn; its cited Source Versions are read through the Turn's Citations.
 */
@Repository
class Feedbacks {

    enum Rating {HELPFUL, INCORRECT, OUTDATED}

    /**
     * A Source Version cited by the rated Turn.
     */
    record CitedSourceVersion(long sourceId, long sourceVersionId, int versionNumber,
                              @JsonUnwrapped SourceDetails source) {
    }

    record FeedbackView(long id, long turnId, String expertId, Rating rating, String comment, OffsetDateTime createdAt,
                        List<CitedSourceVersion> citedSourceVersions) {
    }

    /**
     * A feedback entry enriched with Turn context and cited Source Versions for Knowledge Owner triage.
     */
    record FeedbackQueueItem(long id, long turnId, long conversationId, String expertId, Rating rating, String comment,
                             OffsetDateTime createdAt, String question, ProductCategory productCategory,
                             TurnStatus turnStatus, List<CitedSourceVersion> citedSourceVersions) {
    }

    private record FeedbackRow(long id, long turnId, long conversationId, String expertId, Rating rating,
                               String comment, OffsetDateTime createdAt, String question,
                               ProductCategory productCategory, TurnStatus turnStatus) {
    }

    private final JdbcClient jdbc;

    Feedbacks(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    FeedbackView add(long turnId, String expertId, Rating rating, String comment) {
        return jdbc.sql("""
                        INSERT INTO feedback (turn_id, expert_id, rating, comment)
                        VALUES (:turnId, :expertId, :rating, :comment)
                        RETURNING *
                        """)
                .param("turnId", turnId)
                .param("expertId", expertId)
                .param("rating", SqlEnums.name(rating))
                .param("comment", comment)
                .query(this::feedbackView)
                .single();
    }

    @Transactional(readOnly = true)
    List<FeedbackQueueItem> list(Optional<Rating> rating, Optional<ProductCategory> productCategory, int limit) {
        List<FeedbackRow> rows = jdbc.sql("""
                        SELECT f.id, f.turn_id, t.conversation_id, f.expert_id, f.rating, f.comment, f.created_at,
                               t.question, t.product_category, t.status AS turn_status
                        FROM feedback f
                        JOIN turn t ON t.id = f.turn_id
                        WHERE (:rating::text IS NULL OR f.rating = :rating)
                          AND (:category::text IS NULL OR t.product_category = :category)
                        ORDER BY f.created_at DESC, f.id DESC
                        LIMIT :limit
                        """)
                .param("rating", SqlEnums.name(rating))
                .param("category", SqlEnums.name(productCategory))
                .param("limit", limit)
                .query(Feedbacks::feedbackRow)
                .list();
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, List<CitedSourceVersion>> citedByTurn = citedSourceVersionsByTurn(
                rows.stream().map(FeedbackRow::turnId).distinct().toList());
        return rows.stream()
                .map(row -> new FeedbackQueueItem(row.id(), row.turnId(), row.conversationId(), row.expertId(),
                        row.rating(), row.comment(), row.createdAt(), row.question(), row.productCategory(),
                        row.turnStatus(), citedByTurn.getOrDefault(row.turnId(), List.of())))
                .toList();
    }

    private Map<Long, List<CitedSourceVersion>> citedSourceVersionsByTurn(List<Long> turnIds) {
        Map<Long, List<CitedSourceVersion>> byTurn = new HashMap<>();
        jdbc.sql("""
                        SELECT DISTINCT ON (turn_id, source_version_id) turn_id, source_id, source_version_id,
                               version_number, source_title, url, bank, bank_product, product_category, marker
                        FROM citation
                        WHERE turn_id = ANY(:turnIds::bigint[])
                        ORDER BY turn_id, source_version_id, marker
                        """)
                .param("turnIds", SqlArrays.longLiteral(turnIds))
                .query(rs -> {
                    long turnId = rs.getLong("turn_id");
                    CitedSourceVersion cited = citedSourceVersion(rs);
                    byTurn.computeIfAbsent(turnId, ignored -> new ArrayList<>()).add(cited);
                });
        return byTurn;
    }

    private List<CitedSourceVersion> citedSourceVersions(long turnId) {
        return citedSourceVersionsByTurn(List.of(turnId)).getOrDefault(turnId, List.of());
    }

    private static CitedSourceVersion citedSourceVersion(ResultSet rs) throws SQLException {
        return new CitedSourceVersion(rs.getLong("source_id"), rs.getLong("source_version_id"),
                rs.getInt("version_number"), SourceDetails.from(rs, "source_title"));
    }

    private static FeedbackRow feedbackRow(ResultSet rs, int rowNum) throws SQLException {
        String category = rs.getString("product_category");
        return new FeedbackRow(rs.getLong("id"), rs.getLong("turn_id"), rs.getLong("conversation_id"),
                rs.getString("expert_id"), Rating.valueOf(rs.getString("rating")), rs.getString("comment"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getString("question"),
                category == null ? null : ProductCategory.valueOf(category),
                TurnStatus.valueOf(rs.getString("turn_status")));
    }

    private FeedbackView feedbackView(ResultSet rs, int rowNum) throws SQLException {
        long turnId = rs.getLong("turn_id");
        return new FeedbackView(rs.getLong("id"), turnId, rs.getString("expert_id"),
                Rating.valueOf(rs.getString("rating")), rs.getString("comment"),
                rs.getObject("created_at", OffsetDateTime.class), citedSourceVersions(turnId));
    }
}
