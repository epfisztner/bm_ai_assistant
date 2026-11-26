package com.epfisztner.knowledgeassistant.source;

/**
 * What an ingestion run did with one Source.
 */
public enum IngestionOutcome {
    /**
     * Fetched content has the same hash as the Current Version; nothing new was stored.
     */
    UNCHANGED,
    /**
     * A new Source Version was stored and became the Current Version.
     */
    NEW_VERSION,
    /**
     * Fetch or extraction failed; the Current Version (if any) is untouched.
     */
    FAILED,
    /**
     * The Source is no longer in the Source Registry; it is excluded from retrieval but kept.
     */
    RETIRED
}
