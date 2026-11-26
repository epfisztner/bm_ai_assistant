package com.epfisztner.knowledgeassistant.retrieval;

import com.epfisztner.knowledgeassistant.jdbc.SqlArrays;
import com.epfisztner.knowledgeassistant.source.SourceDetails;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * A Passage of a Current Version found for a Turn, with the Source details needed to present
 * and cite it, and its ranks. {@code lexicalRank}/{@code vectorRank} are 1-based positions in the
 * keyword / semantic result lists (null when the Passage was not in that list); {@code score}
 * is the score the list is ordered by (the fused score once hybrid retrieval exists).
 */
public record RetrievedPassage(long passageId, long sourceVersionId, int versionNumber, long sourceId,
                               SourceDetails source, OffsetDateTime fetchedAt, List<String> headingPath, String text,
                               Integer lexicalRank, Integer vectorRank, double score) {

    /**
     * Maps a retrieval query row (columns {@code passage_id, version_id, version_number, source_id,
     * title, url, bank, bank_product, product_category, fetched_at, heading_path, text, score}), unranked.
     */
    static RetrievedPassage row(ResultSet rs, int rowNum) throws SQLException {
        return new RetrievedPassage(rs.getLong("passage_id"), rs.getLong("version_id"), rs.getInt("version_number"),
                rs.getLong("source_id"), SourceDetails.from(rs, "title"),
                rs.getObject("fetched_at", OffsetDateTime.class), SqlArrays.stringList(rs.getArray("heading_path")),
                rs.getString("text"), null, null, rs.getDouble("score"));
    }

    public RetrievedPassage withRanks(Integer lexicalRank, Integer vectorRank, double score) {
        return new RetrievedPassage(passageId, sourceVersionId, versionNumber, sourceId, source, fetchedAt,
                headingPath, text, lexicalRank, vectorRank, score);
    }
}
