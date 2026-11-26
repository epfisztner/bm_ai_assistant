# Hand-written retrieval SQL instead of Spring AI VectorStore

Spring AI is used only for its chat and embedding model clients; storage and retrieval use hand-written SQL over our own schema rather than Spring AI's `VectorStore`. Retrieval must exclude Superseded Versions, pre-filter by Product Category, and fuse full-text and vector rankings in one query, none of which fit the `VectorStore` table layout and metadata-filter model without workarounds, and Citations must reference our own Source Version and Passage identities.
