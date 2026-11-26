package com.epfisztner.knowledgeassistant.generation;

import com.epfisztner.knowledgeassistant.retrieval.RetrievedPassage;

import java.util.List;
import java.util.Optional;

/**
 * The prompt for one Turn: the Standalone Question and the Passages presented to the model,
 * numbered from 1 (the Citation markers {@code [1]..[n]}), rendered into system and user text.
 */
public record GroundedPrompt(String promptVersion, String system, String user, String question,
                             List<PresentedPassage> passages) {

    /**
     * A retrieved Passage as presented to the model under its Citation marker.
     */
    public record PresentedPassage(int marker, RetrievedPassage passage) {
    }

    public Optional<PresentedPassage> passage(int marker) {
        return passages.stream().filter(p -> p.marker() == marker).findFirst();
    }
}
