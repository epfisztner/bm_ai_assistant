package com.epfisztner.knowledgeassistant.retrieval;

import com.epfisztner.knowledgeassistant.productcategory.ProductCategory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.IntStream;

/**
 * Keyword retrieval: Postgres full-text search with the accent-insensitive Hungarian configuration
 * ({@code hungarian_unaccent}), query terms OR-combined (a natural question need not match every
 * word) and ranked by cover density ({@code ts_rank_cd}). Only Passages of CURRENT versions of
 * ACTIVE Sources; the Product Category is applied as a pre-filter in the WHERE clause.
 * <p>
 * The keyword half of {@link HybridRetriever}.
 */
@Component
class KeywordRetriever {

    private final JdbcClient jdbc;
    private final RetrievalProperties properties;

    KeywordRetriever(JdbcClient jdbc, RetrievalProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    /**
     * Keyword matches above the floor, best first, each with its 1-based lexical rank.
     */
    List<RetrievedPassage> keywordMatches(RetrievalQuery query, int limit) {
        List<RetrievedPassage> rows = jdbc.sql("""
                        WITH q AS (
                            -- plainto_tsquery AND-combines the terms; turn it into an OR query
                            SELECT nullif(replace(plainto_tsquery('hungarian_unaccent', :question)::text, ' & ', ' | '), '')::tsquery AS query
                        )
                        SELECT p.id AS passage_id, v.id AS version_id, v.version_number, s.id AS source_id,
                               coalesce(s.title_override, v.title) AS title, s.url, s.bank, s.bank_product,
                               s.product_category, v.fetched_at, p.heading_path, p.text,
                               ts_rank_cd(p.search_vector, q.query) AS score
                        FROM q
                        JOIN passage p ON p.search_vector @@ q.query
                        JOIN source_version v ON v.id = p.source_version_id AND v.status = 'CURRENT'
                        JOIN source s ON s.id = v.source_id AND s.status = 'ACTIVE'
                        WHERE (:category::text IS NULL OR s.product_category = :category)
                          AND ts_rank_cd(p.search_vector, q.query) > :floor
                        ORDER BY score DESC, p.id
                        LIMIT :limit
                        """)
                .param("question", query.standaloneQuestion())
                .param("category", query.productCategory().map(ProductCategory::name).orElse(null))
                .param("floor", properties.keywordFloor())
                .param("limit", limit)
                .query(RetrievedPassage::row)
                .list();
        return IntStream.range(0, rows.size())
                .mapToObj(i -> rows.get(i).withRanks(i + 1, null, rows.get(i).score()))
                .toList();
    }
}
