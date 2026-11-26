package com.epfisztner.knowledgeassistant.conversation;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.epfisztner.knowledgeassistant.generation.ChatProvider;
import com.epfisztner.knowledgeassistant.jdbc.SqlArrays;
import com.epfisztner.knowledgeassistant.jdbc.SqlEnums;
import com.epfisztner.knowledgeassistant.productcategory.ProductCategory;
import com.epfisztner.knowledgeassistant.retrieval.RetrievedPassage;
import com.epfisztner.knowledgeassistant.rewrite.QuestionRewriter.PriorTurn;
import com.epfisztner.knowledgeassistant.source.KnowledgeBase.SourceStatus;
import com.epfisztner.knowledgeassistant.source.SourceDetails;
import com.epfisztner.knowledgeassistant.source.KnowledgeBase.VersionStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;
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
 * Stores and reads Conversations, Turns and Citations (hand-written SQL, ADR-0005).
 */
@Repository
public class Conversations {

    public enum TurnStatus {ANSWERED, REFUSED}

    public enum RefusalReason {NO_EVIDENCE, MODEL_DECLINED, VERIFICATION_FAILED}

    public record Conversation(long id, String expertId, ProductCategory productCategory, OffsetDateTime createdAt) {
    }

    /**
     * A lightweight Conversation summary for listing, without loading Turns or Citations.
     */
    public record ConversationSummary(long id, ProductCategory productCategory, OffsetDateTime createdAt,
                                      int turnCount, String lastQuestion, OffsetDateTime lastActivityAt) {
    }

    /**
     * A Turn to store. {@code citations} carry the cited Passages as retrieved in this Turn;
     * {@code relevantSources} are the Sources (one Passage each, best first) listed with a Refusal.
     */
    public record NewTurn(String question, String standaloneQuestion, Optional<ProductCategory> productCategory,
                          TurnStatus status, RefusalReason refusalReason, String answerText, long latencyMs,
                          List<NewCitation> citations, List<RetrievedPassage> relevantSources,
                          ChatProvider chatProvider, String chatModel) {
    }

    public record NewCitation(int marker, RetrievedPassage passage) {
    }

    public record TurnView(long id, long conversationId, int ordinal, String question, String standaloneQuestion,
                           ProductCategory productCategory, TurnStatus status, RefusalReason refusalReason,
                           String answer, List<CitationView> citations, long latencyMs, OffsetDateTime createdAt,
                           List<RelevantSourceView> relevantSources, ChatProvider chatProvider, String chatModel) {
    }

    /**
     * A Source listed with a Refusal so the Expert can read it; the version that was retrieved.
     */
    public record RelevantSourceView(long sourceId, long sourceVersionId, int versionNumber,
                                     @JsonUnwrapped SourceDetails source) {
    }

    /**
     * A Citation as returned to the Expert: the snapshot taken at answer time plus the cited
     * version's status now, the Source's current version id when the cited one is superseded, and
     * the Source's status now ({@code RETIRED} once it was dropped from the Source Registry, even
     * though its last version stays CURRENT).
     */
    public record CitationView(int marker, long sourceId, long sourceVersionId, int versionNumber,
                               VersionStatus versionStatus, Long currentVersionId, SourceStatus sourceStatus,
                               @JsonUnwrapped SourceDetails source, OffsetDateTime fetchedAt, long passageId, List<String> passageHeadingPath,
                               String passageText) {
    }

    private final JdbcClient jdbc;

    Conversations(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Conversation start(String expertId, Optional<ProductCategory> productCategory) {
        return jdbc.sql("""
                        INSERT INTO conversation (expert_id, product_category) VALUES (:expertId, :category)
                        RETURNING id, expert_id, product_category, created_at
                        """)
                .param("expertId", expertId)
                .param("category", SqlEnums.name(productCategory))
                .query(Conversations::conversation)
                .single();
    }

    /**
     * The Conversation, only if it belongs to {@code expertId}.
     */
    public Optional<Conversation> find(long id, String expertId) {
        return jdbc.sql("SELECT id, expert_id, product_category, created_at FROM conversation WHERE id = :id AND expert_id = :expertId")
                .param("id", id)
                .param("expertId", expertId)
                .query(Conversations::conversation)
                .optional();
    }

    /**
     * The Expert's Conversations, most recently active first, computed in a single query.
     */
    public List<ConversationSummary> list(String expertId, int limit) {
        return jdbc.sql("""
                        SELECT c.id, c.product_category, c.created_at,
                               stats.turn_count,
                               latest.question AS last_question,
                               COALESCE(latest.created_at, c.created_at) AS last_activity_at
                        FROM conversation c
                        LEFT JOIN LATERAL (
                            SELECT COUNT(*)::int AS turn_count
                            FROM turn
                            WHERE conversation_id = c.id
                        ) stats ON true
                        LEFT JOIN LATERAL (
                            SELECT question, created_at
                            FROM turn
                            WHERE conversation_id = c.id
                            ORDER BY ordinal DESC
                            LIMIT 1
                        ) latest ON true
                        WHERE c.expert_id = :expertId
                        ORDER BY last_activity_at DESC, c.id DESC
                        LIMIT :limit
                        """)
                .param("expertId", expertId)
                .param("limit", limit)
                .query(Conversations::conversationSummary)
                .list();
    }

    /**
     * The Conversation's last {@code limit} Turns, oldest first.
     */
    public List<PriorTurn> recentTurns(long conversationId, int limit) {
        return jdbc.sql("""
                        SELECT question, standalone_question, answer_text FROM (
                            SELECT ordinal, question, standalone_question, answer_text FROM turn
                            WHERE conversation_id = :id ORDER BY ordinal DESC LIMIT :limit
                        ) t ORDER BY ordinal
                        """)
                .param("id", conversationId)
                .param("limit", limit)
                .query(PriorTurn.class)
                .list();
    }

    @Transactional
    public TurnView addTurn(long conversationId, NewTurn turn) {
        jdbc.sql("SELECT id FROM conversation WHERE id = :conversationId FOR UPDATE")
                .param("conversationId", conversationId)
                .query(Long.class)
                .single();
        long turnId = jdbc.sql("""
                        INSERT INTO turn (conversation_id, ordinal, question, standalone_question, product_category,
                                          status, refusal_reason, answer_text, latency_ms, chat_provider, chat_model)
                        SELECT :conversationId, coalesce(max(ordinal), 0) + 1, :question, :standalone, :category,
                               :status, :reason, :answer, :latency, :chatProvider, :chatModel
                        FROM turn WHERE conversation_id = :conversationId
                        RETURNING id
                        """)
                .param("conversationId", conversationId)
                .param("question", turn.question())
                .param("standalone", turn.standaloneQuestion())
                .param("category", SqlEnums.name(turn.productCategory()))
                .param("status", SqlEnums.name(turn.status()))
                .param("reason", SqlEnums.name(turn.refusalReason()))
                .param("answer", turn.answerText())
                .param("latency", turn.latencyMs())
                .param("chatProvider", turn.chatProvider() == null ? null : turn.chatProvider().code())
                .param("chatModel", turn.chatModel())
                .query(Long.class)
                .single();
        for (NewCitation citation : turn.citations()) {
            RetrievedPassage p = citation.passage();
            withSource(jdbc.sql("""
                    INSERT INTO citation (turn_id, marker, passage_id, source_version_id, source_id, version_number,
                                          source_title, url, bank, bank_product, product_category, fetched_at,
                                          passage_heading_path, passage_text)
                    VALUES (:turnId, :marker, :passageId, :versionId, :sourceId, :versionNumber, :title, :url,
                            :bank, :bankProduct, :category, :fetchedAt, :headingPath::text[], :text)
                    """), p.source())
                    .param("turnId", turnId)
                    .param("marker", citation.marker())
                    .param("passageId", p.passageId())
                    .param("versionId", p.sourceVersionId())
                    .param("sourceId", p.sourceId())
                    .param("versionNumber", p.versionNumber())
                    .param("fetchedAt", p.fetchedAt())
                    .param("headingPath", SqlArrays.literal(p.headingPath()))
                    .param("text", p.text())
                    .update();
        }
        int rank = 0;
        for (RetrievedPassage p : turn.relevantSources()) {
            withSource(jdbc.sql("""
                    INSERT INTO turn_relevant_source (turn_id, rank, source_id, source_version_id, version_number,
                                                      source_title, url, bank, bank_product, product_category)
                    VALUES (:turnId, :rank, :sourceId, :versionId, :versionNumber, :title, :url, :bank,
                            :bankProduct, :category)
                    """), p.source())
                    .param("turnId", turnId)
                    .param("rank", ++rank)
                    .param("sourceId", p.sourceId())
                    .param("versionId", p.sourceVersionId())
                    .param("versionNumber", p.versionNumber())
                    .update();
        }
        return turn(turnId).orElseThrow();
    }

    /**
     * Binds the Source snapshot columns {@code :title, :url, :bank, :bankProduct, :category}.
     */
    private static StatementSpec withSource(StatementSpec statement, SourceDetails source) {
        return statement
                .param("title", source.sourceTitle())
                .param("url", source.url())
                .param("bank", source.bank())
                .param("bankProduct", source.bankProduct())
                .param("category", SqlEnums.name(source.productCategory()));
    }

    public Optional<TurnView> turn(long turnId) {
        return jdbc.sql(TURN_SELECT + " WHERE id = :id")
                .param("id", turnId)
                .query(Conversations::turnRow)
                .optional()
                .map(row -> assemble(List.of(row)).getFirst());
    }

    /**
     * The Turn, only if its Conversation belongs to {@code expertId}.
     */
    public Optional<TurnView> ownedTurn(long turnId, String expertId) {
        return jdbc.sql(TURN_SELECT + """
                         WHERE id = :id
                          AND conversation_id IN (SELECT id FROM conversation WHERE expert_id = :expertId)
                        """)
                .param("id", turnId)
                .param("expertId", expertId)
                .query(Conversations::turnRow)
                .optional()
                .map(row -> assemble(List.of(row)).getFirst());
    }

    /**
     * All Turns of the Conversation, in order, with Citations and relevant Sources batch-loaded.
     */
    public List<TurnView> turns(long conversationId) {
        List<TurnRow> rows = jdbc.sql(TURN_SELECT + " WHERE conversation_id = :id ORDER BY ordinal")
                .param("id", conversationId)
                .query(Conversations::turnRow)
                .list();
        return assemble(rows);
    }

    private List<TurnView> assemble(List<TurnRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> turnIds = rows.stream().map(TurnRow::id).toList();
        Map<Long, List<CitationView>> citations = citationsByTurn(turnIds);
        Map<Long, List<RelevantSourceView>> relevantSources = relevantSourcesByTurn(turnIds);
        return rows.stream()
                .map(row -> new TurnView(row.id(), row.conversationId(), row.ordinal(), row.question(),
                        row.standaloneQuestion(), row.productCategory(), row.status(), row.refusalReason(),
                        row.answerText(), citations.getOrDefault(row.id(), List.of()), row.latencyMs(),
                        row.createdAt(), relevantSources.getOrDefault(row.id(), List.of()),
                        row.chatProvider(), row.chatModel()))
                .toList();
    }

    private static final String TURN_SELECT = """
            SELECT id, conversation_id, ordinal, question, standalone_question, product_category, status,
                   refusal_reason, answer_text, latency_ms, created_at, chat_provider, chat_model
            FROM turn
            """;

    private Map<Long, List<CitationView>> citationsByTurn(List<Long> turnIds) {
        return queryByTurn("""
                        SELECT c.*, v.status AS version_status, s.status AS source_status,
                               CASE WHEN v.status = 'CURRENT' THEN NULL ELSE cur.id END AS current_version_id
                        FROM citation c
                        JOIN source_version v ON v.id = c.source_version_id
                        JOIN source s ON s.id = c.source_id
                        LEFT JOIN source_version cur ON cur.source_id = c.source_id AND cur.status = 'CURRENT'
                        WHERE c.turn_id = ANY(:turnIds::bigint[])
                        ORDER BY c.turn_id, c.marker
                        """, turnIds, Conversations::citationView);
    }

    private Map<Long, List<RelevantSourceView>> relevantSourcesByTurn(List<Long> turnIds) {
        return queryByTurn("""
                        SELECT turn_id, source_id, source_version_id, version_number, source_title, url, bank,
                               bank_product, product_category
                        FROM turn_relevant_source
                        WHERE turn_id = ANY(:turnIds::bigint[])
                        ORDER BY turn_id, rank
                        """, turnIds, Conversations::relevantSourceView);
    }

    private <T> Map<Long, List<T>> queryByTurn(String sql, List<Long> turnIds, RowMapper<T> mapper) {
        Map<Long, List<T>> byTurn = new HashMap<>();
        jdbc.sql(sql)
                .param("turnIds", SqlArrays.longLiteral(turnIds))
                .query(rs -> {
                    long turnId = rs.getLong("turn_id");
                    T item = mapper.mapRow(rs, 0);
                    byTurn.computeIfAbsent(turnId, ignored -> new ArrayList<>()).add(item);
                });
        return byTurn;
    }

    private record TurnRow(long id, long conversationId, int ordinal, String question, String standaloneQuestion,
                           ProductCategory productCategory, TurnStatus status, RefusalReason refusalReason,
                           String answerText, long latencyMs, OffsetDateTime createdAt, ChatProvider chatProvider,
                           String chatModel) {
    }

    // --- mapping ----------------------------------------------------------------------------

    private static Conversation conversation(ResultSet rs, int rowNum) throws SQLException {
        return new Conversation(rs.getLong("id"), rs.getString("expert_id"), category(rs.getString("product_category")),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private static ConversationSummary conversationSummary(ResultSet rs, int rowNum) throws SQLException {
        return new ConversationSummary(rs.getLong("id"), category(rs.getString("product_category")),
                rs.getObject("created_at", OffsetDateTime.class), rs.getInt("turn_count"),
                rs.getString("last_question"), rs.getObject("last_activity_at", OffsetDateTime.class));
    }

    private static TurnRow turnRow(ResultSet rs, int rowNum) throws SQLException {
        String reason = rs.getString("refusal_reason");
        return new TurnRow(rs.getLong("id"), rs.getLong("conversation_id"), rs.getInt("ordinal"),
                rs.getString("question"), rs.getString("standalone_question"), category(rs.getString("product_category")),
                TurnStatus.valueOf(rs.getString("status")), reason == null ? null : RefusalReason.valueOf(reason),
                rs.getString("answer_text"), rs.getLong("latency_ms"),
                rs.getObject("created_at", OffsetDateTime.class),
                chatProvider(rs.getString("chat_provider")), rs.getString("chat_model"));
    }

    private static CitationView citationView(ResultSet rs, int rowNum) throws SQLException {
        long current = rs.getLong("current_version_id");
        Long currentVersionId = rs.wasNull() ? null : current;
        return new CitationView(rs.getInt("marker"), rs.getLong("source_id"), rs.getLong("source_version_id"),
                rs.getInt("version_number"), VersionStatus.valueOf(rs.getString("version_status")), currentVersionId,
                SourceStatus.valueOf(rs.getString("source_status")), SourceDetails.from(rs, "source_title"),
                rs.getObject("fetched_at", OffsetDateTime.class), rs.getLong("passage_id"),
                SqlArrays.stringList(rs.getArray("passage_heading_path")), rs.getString("passage_text"));
    }

    private static RelevantSourceView relevantSourceView(ResultSet rs, int rowNum) throws SQLException {
        return new RelevantSourceView(rs.getLong("source_id"), rs.getLong("source_version_id"),
                rs.getInt("version_number"), SourceDetails.from(rs, "source_title"));
    }

    private static ProductCategory category(String code) {
        return code == null ? null : ProductCategory.valueOf(code);
    }

    private static ChatProvider chatProvider(String code) {
        return code == null ? null : ChatProvider.fromCode(code);
    }
}
