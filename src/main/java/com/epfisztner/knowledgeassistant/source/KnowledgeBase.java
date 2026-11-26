package com.epfisztner.knowledgeassistant.source;

import com.epfisztner.knowledgeassistant.jdbc.SqlArrays;
import com.epfisztner.knowledgeassistant.jdbc.SqlEnums;
import com.epfisztner.knowledgeassistant.productcategory.ProductCategory;
import com.epfisztner.knowledgeassistant.source.SourceRegistry.RegisteredSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Stores and reads Sources, their Source Versions and Passages (hand-written SQL, ADR-0005).
 * Source Versions are immutable once written; the only change is CURRENT -> SUPERSEDED when a
 * newer version is added.
 */
@Repository
public class KnowledgeBase {

    public enum VersionStatus {CURRENT, SUPERSEDED}

    public enum SourceStatus {ACTIVE, RETIRED}

    /**
     * A version's identity and content hash.
     */
    public record VersionRef(long id, int versionNumber, String contentHash, OffsetDateTime fetchedAt) {
    }

    public record SourceView(long id, String key, String url, String title, ProductCategory productCategory,
                             String bank, String bankProduct, SourceStatus status, VersionRef currentVersion,
                             OffsetDateTime lastFetchAttemptAt, IngestionOutcome lastFetchOutcome,
                             String lastFetchError) {
    }

    public record VersionSummary(long id, long sourceId, int versionNumber, VersionStatus status, String title,
                                 String contentHash, OffsetDateTime fetchedAt, OffsetDateTime supersededAt) {
    }

    /**
     * {@code embeddingModel} is the id of the model that produced the Passage's embedding (null: not embedded).
     */
    public record PassageView(long id, int ordinal, List<String> headingPath, String text, String embeddingModel) {
    }

    public record VersionDetail(VersionSummary version, String extractedText, List<PassageView> passages) {
    }

    /**
     * Content of a Source Version to be stored.
     */
    public record NewVersion(String contentHash, String rawContent, String title, String extractedText,
                             OffsetDateTime fetchedAt, List<NewPassage> passages) {
    }

    public record NewPassage(int ordinal, List<String> headingPath, String text) {
    }

    private final JdbcClient jdbc;

    KnowledgeBase(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // --- writes -----------------------------------------------------------------------------

    /**
     * Creates the Source or updates its registry metadata; returns its id. The Source becomes ACTIVE.
     */
    @Transactional
    public long register(RegisteredSource source) {
        return jdbc.sql("""
                        INSERT INTO source (registry_key, url, title_override, product_category, bank, bank_product, status)
                        VALUES (:key, :url, :title, :category, :bank, :bankProduct, 'ACTIVE')
                        ON CONFLICT (registry_key) DO UPDATE SET
                            url = excluded.url, title_override = excluded.title_override,
                            product_category = excluded.product_category, bank = excluded.bank,
                            bank_product = excluded.bank_product, status = 'ACTIVE'
                        RETURNING id
                        """)
                .param("key", source.key())
                .param("url", source.url())
                .param("title", source.title())
                .param("category", SqlEnums.name(source.productCategory()))
                .param("bank", source.bank())
                .param("bankProduct", source.bankProduct())
                .query(Long.class)
                .single();
    }

    /**
     * Stores {@code version} as the Source's new Current Version (next version number) with its
     * Passages; the previous Current Version, if any, becomes SUPERSEDED.
     */
    @Transactional
    public VersionRef addVersion(long sourceId, NewVersion version) {
        jdbc.sql("""
                        UPDATE source_version SET status = 'SUPERSEDED', superseded_at = :at
                        WHERE source_id = :sourceId AND status = 'CURRENT'
                        """)
                .param("sourceId", sourceId)
                .param("at", version.fetchedAt())
                .update();
        int versionNumber = jdbc.sql("SELECT coalesce(max(version_number), 0) + 1 FROM source_version WHERE source_id = :sourceId")
                .param("sourceId", sourceId)
                .query(Integer.class)
                .single();
        long versionId = jdbc.sql("""
                        INSERT INTO source_version (source_id, version_number, content_hash, raw_content, title,
                                                    extracted_text, fetched_at, status)
                        VALUES (:sourceId, :versionNumber, :hash, :raw, :title, :text, :fetchedAt, 'CURRENT')
                        RETURNING id
                        """)
                .param("sourceId", sourceId)
                .param("versionNumber", versionNumber)
                .param("hash", version.contentHash())
                .param("raw", version.rawContent())
                .param("title", version.title())
                .param("text", version.extractedText())
                .param("fetchedAt", version.fetchedAt())
                .query(Long.class)
                .single();
        for (NewPassage passage : version.passages()) {
            // The search vector covers the Source's labels (title, bank, Bank Product) and the
            // Passage's heading path, so a Passage is found by what it is about, not only its words.
            jdbc.sql("""
                            INSERT INTO passage (source_version_id, ordinal, heading_path, text, search_vector)
                            SELECT :versionId, :ordinal, :headingPath::text[], :text,
                                   to_tsvector('hungarian_unaccent',
                                       concat_ws(' ', coalesce(s.title_override, :title), s.bank, s.bank_product,
                                                 array_to_string(:headingPath::text[], ' '), :text))
                            FROM source s WHERE s.id = :sourceId
                            """)
                    .param("versionId", versionId)
                    .param("ordinal", passage.ordinal())
                    .param("headingPath", SqlArrays.literal(passage.headingPath()))
                    .param("text", passage.text())
                    .param("title", version.title())
                    .param("sourceId", sourceId)
                    .update();
        }
        return new VersionRef(versionId, versionNumber, version.contentHash(), version.fetchedAt());
    }

    /**
     * A Source that was just retired, with its (kept) Current Version, if it has one.
     */
    public record RetiredSource(long id, String key, Long currentVersionId) {
    }

    /**
     * Retires every ACTIVE Source whose registry key is not in {@code registryKeys}: it is excluded
     * from retrieval from now on, while its Source Versions and Passages are kept.
     */
    @Transactional
    public List<RetiredSource> retireAllExcept(Collection<String> registryKeys) {
        return jdbc.sql("""
                        UPDATE source s SET status = 'RETIRED'
                        WHERE s.status = 'ACTIVE' AND NOT (s.registry_key = ANY (:keys::text[]))
                        RETURNING s.id, s.registry_key AS key,
                                  (SELECT v.id FROM source_version v WHERE v.source_id = s.id AND v.status = 'CURRENT') AS current_version_id
                        """)
                .param("keys", SqlArrays.literal(List.copyOf(registryKeys)))
                .query(RetiredSource.class)
                .list()
                .stream()
                .sorted(Comparator.comparing(RetiredSource::key))
                .toList();
    }

    /**
     * Records the latest fetch attempt and its outcome on the Source.
     */
    @Transactional
    public void recordFetch(long sourceId, OffsetDateTime attemptedAt, IngestionOutcome outcome, String error) {
        jdbc.sql("""
                        UPDATE source SET last_fetch_attempt_at = :at, last_fetch_outcome = :outcome, last_fetch_error = :error
                        WHERE id = :id
                        """)
                .param("id", sourceId)
                .param("at", attemptedAt)
                .param("outcome", SqlEnums.name(outcome))
                .param("error", error)
                .update();
    }

    // --- reads ------------------------------------------------------------------------------

    /**
     * True when no Source Version has ever been stored.
     */
    public boolean isEmpty() {
        return !jdbc.sql("SELECT exists(SELECT 1 FROM source_version)").query(Boolean.class).single();
    }

    public Optional<VersionRef> currentVersion(long sourceId) {
        return jdbc.sql("""
                        SELECT id, version_number, content_hash, fetched_at FROM source_version
                        WHERE source_id = :sourceId AND status = 'CURRENT'
                        """)
                .param("sourceId", sourceId)
                .query(VersionRef.class)
                .optional();
    }

    private static final String SOURCE_SELECT = """
            SELECT s.id, s.registry_key, s.url, coalesce(s.title_override, v.title) AS title, s.product_category,
                   s.bank, s.bank_product, s.status, s.last_fetch_attempt_at, s.last_fetch_outcome, s.last_fetch_error,
                   v.id AS version_id, v.version_number, v.content_hash, v.fetched_at
            FROM source s
            LEFT JOIN source_version v ON v.source_id = s.id AND v.status = 'CURRENT'
            """;

    public List<SourceView> sources(Optional<ProductCategory> productCategory) {
        return jdbc.sql(SOURCE_SELECT + " WHERE (:category::text IS NULL OR s.product_category = :category) ORDER BY s.registry_key")
                .param("category", SqlEnums.name(productCategory))
                .query(KnowledgeBase::sourceView)
                .list();
    }

    public Optional<SourceView> source(long id) {
        return jdbc.sql(SOURCE_SELECT + " WHERE s.id = :id")
                .param("id", id)
                .query(KnowledgeBase::sourceView)
                .optional();
    }

    /**
     * The Source's versions, newest first.
     */
    public List<VersionSummary> versions(long sourceId) {
        return jdbc.sql(VERSION_SELECT + " WHERE source_id = :sourceId ORDER BY version_number DESC")
                .param("sourceId", sourceId)
                .query(KnowledgeBase::versionSummary)
                .list();
    }

    public Optional<VersionDetail> version(long id) {
        return jdbc.sql(VERSION_SELECT + " WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> new VersionDetail(versionSummary(rs, n), rs.getString("extracted_text"), passages(id)))
                .optional();
    }

    private static final String VERSION_SELECT = """
            SELECT id, source_id, version_number, status, title, content_hash, fetched_at, superseded_at, extracted_text
            FROM source_version
            """;

    private List<PassageView> passages(long versionId) {
        return jdbc.sql("SELECT id, ordinal, heading_path, text, embedding_model FROM passage WHERE source_version_id = :versionId ORDER BY ordinal")
                .param("versionId", versionId)
                .query((rs, n) -> new PassageView(rs.getLong("id"), rs.getInt("ordinal"),
                        SqlArrays.stringList(rs.getArray("heading_path")), rs.getString("text"), rs.getString("embedding_model")))
                .list();
    }

    // --- mapping ----------------------------------------------------------------------------

    private static SourceView sourceView(ResultSet rs, int rowNum) throws SQLException {
        long versionId = rs.getLong("version_id");
        VersionRef current = rs.wasNull() ? null : new VersionRef(versionId, rs.getInt("version_number"),
                rs.getString("content_hash"), rs.getObject("fetched_at", OffsetDateTime.class));
        String outcome = rs.getString("last_fetch_outcome");
        return new SourceView(rs.getLong("id"), rs.getString("registry_key"), rs.getString("url"), rs.getString("title"),
                ProductCategory.valueOf(rs.getString("product_category")), rs.getString("bank"),
                rs.getString("bank_product"), SourceStatus.valueOf(rs.getString("status")), current,
                rs.getObject("last_fetch_attempt_at", OffsetDateTime.class),
                outcome == null ? null : IngestionOutcome.valueOf(outcome), rs.getString("last_fetch_error"));
    }

    private static VersionSummary versionSummary(ResultSet rs, int rowNum) throws SQLException {
        return new VersionSummary(rs.getLong("id"), rs.getLong("source_id"), rs.getInt("version_number"),
                VersionStatus.valueOf(rs.getString("status")), rs.getString("title"), rs.getString("content_hash"),
                rs.getObject("fetched_at", OffsetDateTime.class), rs.getObject("superseded_at", OffsetDateTime.class));
    }
}
