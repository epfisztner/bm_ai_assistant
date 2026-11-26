package com.epfisztner.knowledgeassistant.retrieval;

import java.util.List;
import java.util.Optional;

/**
 * Everything one retrieval found, for the prompt and the audit trail: all fused candidates, best
 * first, each with its lexical rank, vector rank and fused score ({@link RetrievedPassage}) and
 * whether it went into the prompt (the top N), plus the embedding model the semantic half used
 * (empty when retrieval was keyword-only).
 */
public record Retrieval(List<Candidate> candidates, Optional<String> embeddingModel) {

    public record Candidate(RetrievedPassage passage, boolean inPrompt) {
    }

    /**
     * The Passages that go into the prompt, best first.
     */
    public List<RetrievedPassage> presented() {
        return candidates.stream().filter(Candidate::inPrompt).map(Candidate::passage).toList();
    }
}
