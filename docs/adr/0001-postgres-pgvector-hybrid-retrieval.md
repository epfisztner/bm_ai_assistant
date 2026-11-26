# PostgreSQL + pgvector with hybrid retrieval

The Knowledge Base, Conversations and audit trail live in PostgreSQL with the pgvector extension, and retrieval fuses Postgres full-text search (Hungarian stemmer) with vector similarity via reciprocal-rank fusion. Exact-term matching matters as much as semantic similarity here (bank names, fee names, amounts like "225 ezer Ft"), Postgres gives both in one transactional store together with version status and Product Category filtering, and it maps directly to Aurora PostgreSQL in production. Full-text-only retrieval remains a working fallback when no embedding provider is configured.

## Considered Options

- **MySQL / Aurora MySQL** (already in the stack): no production-grade vector search; would need a second store.
- **MongoDB Atlas Vector Search** (already in the stack): viable in production, but the local Atlas image is heavy and the hybrid query is harder to express together with version and category filters.
- **OpenSearch**: strong hybrid search, but a second datastore to operate and keep consistent with versioning and audit data.
