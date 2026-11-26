package com.epfisztner.knowledgeassistant.retrieval;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param keywordTopK  how many keyword matches to consider
 * @param topN         how many Passages go into the prompt
 * @param keywordFloor minimum cover-density rank (ts_rank_cd) for a keyword match to count as evidence
 * @param vectorTopK   how many semantic (embedding) matches to consider
 * @param vectorFloor  minimum cosine similarity for a semantic match to count as evidence
 * @param rrfK         reciprocal-rank fusion constant: fused score = sum over both lists of 1 / (rrfK + rank)
 */
@ConfigurationProperties("knowledge.retrieval")
public record RetrievalProperties(int keywordTopK, int topN, double keywordFloor, int vectorTopK, double vectorFloor,
                                  int rrfK) {
}
