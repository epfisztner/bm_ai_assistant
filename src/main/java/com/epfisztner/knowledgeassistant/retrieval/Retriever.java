package com.epfisztner.knowledgeassistant.retrieval;

/**
 * Finds the Passages relevant to a Question among Passages of CURRENT versions of ACTIVE Sources.
 * The {@link Retrieval} holds every candidate above the relevance floor, best first, and marks the
 * top N that go into the prompt ({@link Retrieval#presented()}); no presented Passage means there
 * is no evidence.
 */
public interface Retriever {

    Retrieval retrieve(RetrievalQuery query);
}
