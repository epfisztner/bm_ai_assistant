package com.epfisztner.knowledgeassistant.conversation;

import com.epfisztner.knowledgeassistant.generation.ChatProvider;
import com.epfisztner.knowledgeassistant.jdbc.SqlArrays;
import com.epfisztner.knowledgeassistant.retrieval.Retrieval;
import com.epfisztner.knowledgeassistant.retrieval.RetrievedPassage;
import com.epfisztner.knowledgeassistant.rewrite.QuestionRewriter.Rewrite;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Stores and reads the Turn audit record (hand-written SQL, ADR-0005).
 */
@Repository
class TurnAudits {

    /**
     * How a Turn was produced (the chat provider/model are stored on the Turn). Prompt fields are
     * null when the chat model was not called; {@code verdictPassed}/{@code failedChecks} are null
     * when nothing was verified. {@code markers} maps presented Passage ids to their prompt marker.
     */
    record NewTurnAudit(Rewrite rewrite, Retrieval retrieval, Map<Long, Integer> markers, String promptVersion,
                        String rawModelOutput, Boolean verdictPassed, List<String> failedChecks) {
    }

    record AuditRecord(RewriteView rewrite, String embeddingModel, String promptVersion, String rawModelOutput,
                       VerdictView verdict, List<AuditedPassage> retrievedPassages) {
    }

    /**
     * How a follow-up became its Standalone Question: the rewrite prompt version, the model's raw
     * reply and its provider/model, and whether a blank reply fell back to joining the previous
     * Standalone Question with the Question.
     */
    record RewriteView(String promptVersion, String rawOutput, ChatProvider chatProvider, String chatModel,
                       boolean fellBackToJoin) {
    }

    record VerdictView(boolean passed, List<String> failedChecks) {
    }

    /**
     * A retrieval candidate of the Turn, best first, and whether it went into the prompt (with its marker).
     */
    record AuditedPassage(int rank, long passageId, long sourceId, long sourceVersionId, int versionNumber,
                          String sourceTitle, String bankProduct, List<String> passageHeadingPath,
                          Integer lexicalRank, Integer vectorRank, double score, boolean inPrompt, Integer marker) {
    }

    private final JdbcClient jdbc;

    TurnAudits(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void record(long turnId, NewTurnAudit audit) {
        Rewrite rewrite = audit.rewrite();
        jdbc.sql("""
                        INSERT INTO turn_audit (turn_id, embedding_model, prompt_version, raw_model_output,
                                                verdict_passed, failed_checks, rewrite_prompt_version,
                                                rewrite_raw_output, rewrite_chat_provider, rewrite_chat_model,
                                                rewrite_fell_back)
                        VALUES (:turnId, :embeddingModel, :promptVersion, :raw, :passed, :failedChecks::text[],
                                :rewriteVersion, :rewriteRaw, :rewriteProvider, :rewriteModel, :rewriteFellBack)
                        """)
                .param("turnId", turnId)
                .param("rewriteVersion", rewrite.modelAsked() ? rewrite.promptVersion() : null)
                .param("rewriteRaw", rewrite.modelAsked() ? rewrite.reply().text() : null)
                .param("rewriteProvider", rewrite.modelAsked() ? rewrite.reply().provider().code() : null)
                .param("rewriteModel", rewrite.modelAsked() ? rewrite.reply().model() : null)
                .param("rewriteFellBack", rewrite.modelAsked() ? rewrite.fellBackToJoin() : null)
                .param("embeddingModel", audit.retrieval().embeddingModel().orElse(null))
                .param("promptVersion", audit.promptVersion())
                .param("raw", audit.rawModelOutput())
                .param("passed", audit.verdictPassed())
                .param("failedChecks", audit.failedChecks() == null ? null : SqlArrays.literal(audit.failedChecks()))
                .update();
        int rank = 0;
        for (Retrieval.Candidate candidate : audit.retrieval().candidates()) {
            RetrievedPassage p = candidate.passage();
            jdbc.sql("""
                            INSERT INTO turn_audit_passage (turn_id, rank, passage_id, source_version_id, source_id,
                                                            version_number, source_title, bank_product,
                                                            passage_heading_path, lexical_rank, vector_rank, score,
                                                            in_prompt, marker)
                            VALUES (:turnId, :rank, :passageId, :versionId, :sourceId, :versionNumber, :title,
                                    :bankProduct, :headingPath::text[], :lexicalRank, :vectorRank, :score,
                                    :inPrompt, :marker)
                            """)
                    .param("turnId", turnId)
                    .param("rank", ++rank)
                    .param("passageId", p.passageId())
                    .param("versionId", p.sourceVersionId())
                    .param("sourceId", p.sourceId())
                    .param("versionNumber", p.versionNumber())
                    .param("title", p.source().sourceTitle())
                    .param("bankProduct", p.source().bankProduct())
                    .param("headingPath", SqlArrays.literal(p.headingPath()))
                    .param("lexicalRank", p.lexicalRank())
                    .param("vectorRank", p.vectorRank())
                    .param("score", p.score())
                    .param("inPrompt", candidate.inPrompt())
                    .param("marker", audit.markers().get(p.passageId()))
                    .update();
        }
    }

    /**
     * The audit record of the Turn; empty for Turns stored before auditing existed.
     */
    Optional<AuditRecord> find(long turnId) {
        return jdbc.sql("SELECT * FROM turn_audit WHERE turn_id = :turnId")
                .param("turnId", turnId)
                .query((rs, n) -> new AuditRecord(rewrite(rs), rs.getString("embedding_model"),
                        rs.getString("prompt_version"), rs.getString("raw_model_output"), verdict(rs),
                        passages(turnId)))
                .optional();
    }

    private static RewriteView rewrite(ResultSet rs) throws SQLException {
        Boolean fellBack = rs.getObject("rewrite_fell_back", Boolean.class);
        return fellBack == null ? null : new RewriteView(rs.getString("rewrite_prompt_version"),
                rs.getString("rewrite_raw_output"), ChatProvider.fromCode(rs.getString("rewrite_chat_provider")),
                rs.getString("rewrite_chat_model"), fellBack);
    }

    private List<AuditedPassage> passages(long turnId) {
        return jdbc.sql("SELECT * FROM turn_audit_passage WHERE turn_id = :turnId ORDER BY rank")
                .param("turnId", turnId)
                .query((rs, n) -> new AuditedPassage(rs.getInt("rank"), rs.getLong("passage_id"),
                        rs.getLong("source_id"), rs.getLong("source_version_id"), rs.getInt("version_number"),
                        rs.getString("source_title"), rs.getString("bank_product"),
                        SqlArrays.stringList(rs.getArray("passage_heading_path")), rs.getObject("lexical_rank", Integer.class),
                        rs.getObject("vector_rank", Integer.class), rs.getDouble("score"), rs.getBoolean("in_prompt"),
                        rs.getObject("marker", Integer.class)))
                .list();
    }

    private static VerdictView verdict(ResultSet rs) throws SQLException {
        Boolean passed = rs.getObject("verdict_passed", Boolean.class);
        return passed == null ? null : new VerdictView(passed, SqlArrays.stringList(rs.getArray("failed_checks")));
    }
}
