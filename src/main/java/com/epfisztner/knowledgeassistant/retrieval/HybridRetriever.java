package com.epfisztner.knowledgeassistant.retrieval;

import com.epfisztner.knowledgeassistant.embedding.EmbeddingModelPort;
import com.epfisztner.knowledgeassistant.embedding.PassageEmbeddings;
import com.epfisztner.knowledgeassistant.productcategory.ProductCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

/**
 * Hybrid retrieval (ADR-0001): the top K keyword matches ({@link KeywordRetriever}) and, when an
 * embedding model is configured, the top K semantic matches (cosine similarity of Passage
 * embeddings made by the same model) are fused with reciprocal-rank fusion; the top N go into the
 * prompt. Each half applies its own relevance floor, so a candidate is evidence when it passes
 * either. Without an embedding model, or when embedding the Question fails, it is keyword-only.
 */
@Component
class HybridRetriever implements Retriever {

    private static final Logger log = LoggerFactory.getLogger(HybridRetriever.class);

    private final KeywordRetriever keyword;
    private final JdbcClient jdbc;
    private final RetrievalProperties properties;
    private final Optional<EmbeddingModelPort> embeddingModel;

    HybridRetriever(KeywordRetriever keyword, JdbcClient jdbc, RetrievalProperties properties,
                    Optional<EmbeddingModelPort> embeddingModel) {
        this.keyword = keyword;
        this.jdbc = jdbc;
        this.properties = properties;
        this.embeddingModel = embeddingModel;
    }

    @Override
    public Retrieval retrieve(RetrievalQuery query) {
        List<RetrievedPassage> lexical = keyword.keywordMatches(query, properties.keywordTopK());
        Optional<SemanticMatches> semantic = embeddingModel.flatMap(model -> semanticMatches(query, model));
        List<RetrievedPassage> fused = fuse(lexical, semantic.map(SemanticMatches::passages).orElse(List.of()));
        List<Retrieval.Candidate> candidates = IntStream.range(0, fused.size())
                .mapToObj(i -> new Retrieval.Candidate(fused.get(i), i < properties.topN()))
                .toList();
        return new Retrieval(candidates, semantic.map(SemanticMatches::modelId));
    }

    /**
     * Reciprocal-rank fusion; ties go to the better lexical rank (exact terms matter), then passage id.
     */
    private List<RetrievedPassage> fuse(List<RetrievedPassage> lexical, List<RetrievedPassage> semantic) {
        Map<Long, RetrievedPassage> byPassage = new LinkedHashMap<>();
        for (RetrievedPassage p : lexical) {
            byPassage.put(p.passageId(), p.withRanks(p.lexicalRank(), null, 0));
        }
        for (RetrievedPassage p : semantic) {
            RetrievedPassage known = byPassage.getOrDefault(p.passageId(), p);
            byPassage.put(p.passageId(), known.withRanks(known.lexicalRank(), p.vectorRank(), 0));
        }
        return byPassage.values().stream()
                .map(p -> p.withRanks(p.lexicalRank(), p.vectorRank(), rrf(p.lexicalRank()) + rrf(p.vectorRank())))
                .sorted(Comparator.comparingDouble(RetrievedPassage::score).reversed()
                        .thenComparing(RetrievedPassage::lexicalRank, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparingLong(RetrievedPassage::passageId))
                .toList();
    }

    private double rrf(Integer rank) {
        return rank == null ? 0 : 1.0 / (properties.rrfK() + rank);
    }

    private record SemanticMatches(String modelId, List<RetrievedPassage> passages) {
    }

    private Optional<SemanticMatches> semanticMatches(RetrievalQuery query, EmbeddingModelPort model) {
        String modelId = model.modelId();
        float[] questionVector;
        try {
            questionVector = model.embed(List.of(query.standaloneQuestion())).getFirst();
        } catch (RuntimeException e) {
            log.warn("Embedding the Question failed; retrieving by keywords only", e);
            return Optional.empty();
        }
        // Only Passages embedded by the same model are compared; distance < 1 - floor also rejects NaN.
        List<RetrievedPassage> rows = jdbc.sql("""
                        SELECT p.id AS passage_id, v.id AS version_id, v.version_number, s.id AS source_id,
                               coalesce(s.title_override, v.title) AS title, s.url, s.bank, s.bank_product,
                               s.product_category, v.fetched_at, p.heading_path, p.text,
                               1 - (p.embedding <=> :question::vector) AS score
                        FROM passage p
                        JOIN source_version v ON v.id = p.source_version_id AND v.status = 'CURRENT'
                        JOIN source s ON s.id = v.source_id AND s.status = 'ACTIVE'
                        WHERE p.embedding_model = :model
                          AND (:category::text IS NULL OR s.product_category = :category)
                          AND (p.embedding <=> :question::vector) < 1 - :floor
                        ORDER BY p.embedding <=> :question::vector, p.id
                        LIMIT :limit
                        """)
                .param("question", PassageEmbeddings.vectorLiteral(questionVector))
                .param("model", modelId)
                .param("category", query.productCategory().map(ProductCategory::name).orElse(null))
                .param("floor", properties.vectorFloor())
                .param("limit", properties.vectorTopK())
                .query(RetrievedPassage::row)
                .list();
        List<RetrievedPassage> ranked = IntStream.range(0, rows.size())
                .mapToObj(i -> rows.get(i).withRanks(null, i + 1, rows.get(i).score()))
                .toList();
        return Optional.of(new SemanticMatches(modelId, ranked));
    }
}
