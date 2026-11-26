# Expert Knowledge Assistant

An internal assistant that answers Experts' questions (in Hungarian) **strictly from a versioned Knowledge Base**,
with a Citation to the exact Source Version behind every statement. Answers are verified before release; when the
evidence is missing or the Answer cannot be verified, the assistant gives a Refusal instead of guessing.

This repository is a proof of concept built on public [bankmonitor.hu](https://bankmonitor.hu) pages about
bank accounts (bankszámla), mortgages (lakáshitel) and personal loans (személyi kölcsön).

- Domain language: [GLOSSARY.md](GLOSSARY.md)
- Architecture decisions: [docs/adr/](docs/adr/)
- Proposal for the business (Hungarian): [docs/proposal.hu.md](docs/proposal.hu.md)
- Manual smoke run: [docs/smoke-test.md](docs/smoke-test.md)

## How it works

1. **Ingestion.** The [Source Registry](src/main/resources/source-registry.yaml) lists the Sources (URL, Product
   Category, bank, Bank Product). The service fetches each page, extracts the text, and stores every distinct content
   as an immutable **Source Version** split into **Passages**. Changed content creates a new version and the old one
   becomes superseded; a Source removed from the registry is retired. The Knowledge Base is seeded on first start.
2. **A Turn.** The Expert's Question is rewritten into a **Standalone Question** using earlier Turns (history is
   never evidence, [ADR-0003](docs/adr/0003-conversation-history-is-never-evidence.md)), Passages of Current Versions
   are retrieved (Postgres full-text search, plus pgvector similarity when an embedding model is configured, fused by
   reciprocal rank, [ADR-0001](docs/adr/0001-postgres-pgvector-hybrid-retrieval.md)), optionally pre-filtered by
   Product Category.
3. **Answer and verification.** The chat model answers only from the numbered Passages, citing them as `[n]`. Every
   paragraph must carry a valid marker and every figure must appear in the cited Passages, otherwise the Turn is a
   Refusal ([ADR-0002](docs/adr/0002-verify-before-release-no-streaming.md)).
4. **Audit and feedback.** Every Turn is audited (question rewrite, retrieved Passages with ranks, model, prompt
   version, raw model output, verdict), and Experts can rate Answers as helpful, incorrect or outdated for the Knowledge Owner.

Stack: Java 21, Spring Boot 4, Spring AI (chat clients only), PostgreSQL 17 + pgvector, Flyway, hand-written SQL
([ADR-0005](docs/adr/0005-hand-written-sql-over-spring-ai-vectorstore.md),
[ADR-0006](docs/adr/0006-jdbcclient-for-mvp-jooq-for-production.md)), springdoc OpenAPI.

## Quick start

Requirements: Docker with Docker Compose.

```bash
docker compose up --build
```

This builds the service image (multi-stage [Dockerfile](Dockerfile)), starts Postgres and the service, and seeds the
Knowledge Base from bankmonitor.hu on first start (a few seconds; the service reports ready afterwards). Then open:

- API test console (Hungarian, for trying the API by hand; not the Expert UI): <http://localhost:8080/>
- Swagger UI: <http://localhost:8080/swagger-ui.html>
- OpenAPI document: <http://localhost:8080/v3/api-docs>
- Probes: <http://localhost:8080/actuator/health/readiness>, `/actuator/health/liveness`

The default chat provider is **offline**: a deterministic extractive stand-in that needs no API key or network
access. It quotes the best-matching sentences of the retrieved Passages verbatim, so it shows retrieval, Citations,
verification and auditing end to end, but it does not reason. Configure a real model (below) to see real Answers.

Ports clash with something local? `POSTGRES_PORT=25432 APP_PORT=18080 docker compose up --build`.
Start from an empty Knowledge Base: `docker compose down -v`.

## Configuration

Compose reads settings from the shell environment or from a `.env` file next to `compose.yaml`
(see [compose.yaml](compose.yaml) for every variable and its default). The chat provider and the embedding provider
are independent of each other.

| Variable | Default (Compose) | Meaning |
|---|---|---|
| `CHAT_PROVIDER` | `offline` | `offline`, `openai-compatible` or `anthropic` |
| `CHAT_OPENAI_BASE_URL` | `http://host.docker.internal:1234/v1` | OpenAI-compatible endpoint (LM Studio, Ollama, OpenAI) |
| `CHAT_OPENAI_MODEL` | `qwen/qwen3.5-9b` | model id as the server knows it |
| `CHAT_OPENAI_API_KEY` | empty | optional for local servers |
| `ANTHROPIC_API_KEY` | empty | required for `anthropic` |
| `CHAT_ANTHROPIC_MODEL` | `claude-sonnet-4-6` | Anthropic model id |
| `EMBEDDING_PROVIDER` | `none` | `none` (keyword-only retrieval) or `openai-compatible` (hybrid) |
| `EMBEDDING_BASE_URL` | `http://host.docker.internal:1234/v1` | OpenAI-compatible `/embeddings` endpoint |
| `EMBEDDING_MODEL` | `text-embedding-nomic-embed-text-v1.5` | embedding model id |
| `EMBEDDING_API_KEY`, `EMBEDDING_DIMENSIONS` | empty | optional |
| `SEED_ON_STARTUP` | `true` | ingest all Sources on startup when the Knowledge Base is empty |
| `SNAPSHOT_MODE` | `record` | `off`, `record` (save fetched pages) or `replay` (read saved pages, no network) |
| `INGESTION_CRON` | `-` (off) | scheduled re-ingestion, Spring cron, e.g. `0 0 5 * * *` |

Every other setting lives in [application.yaml](src/main/resources/application.yaml) under `knowledge.*`
(retrieval sizes and floors, history length, timeouts) and can be overridden the usual Spring Boot way, e.g.
`KNOWLEDGE_RETRIEVAL_KEYWORDFLOOR=0.05`.

### Offline (default)

```bash
docker compose up --build
```

### LM Studio (OpenAI-compatible chat and embeddings)

Start the LM Studio server (default port 1234) with a chat model and an embedding model loaded, then:

```bash
CHAT_PROVIDER=openai-compatible CHAT_OPENAI_MODEL=qwen/qwen3.5-9b \
EMBEDDING_PROVIDER=openai-compatible EMBEDDING_MODEL=text-embedding-nomic-embed-text-v1.5 \
docker compose up --build
```

Inside the container LM Studio is reached as `host.docker.internal`. Passages are embedded at the end of every
ingestion run and on startup, so switching the embedding model on an existing Knowledge Base re-embeds it. The same
variables work for Ollama (`http://host.docker.internal:11434/v1`) or OpenAI (`https://api.openai.com/v1` plus a key).

### Anthropic

```bash
CHAT_PROVIDER=anthropic ANTHROPIC_API_KEY=... docker compose up --build
```

Embeddings stay optional: combine with the `EMBEDDING_*` variables above for hybrid retrieval.

### Snapshot and replay

Compose runs with `SNAPSHOT_MODE=record`: every fetched page is saved to the `snapshots` volume as
`<source key>.html`. To work without network access later (or to reproduce a run exactly), replay them:

```bash
SNAPSHOT_MODE=replay docker compose up
```

A Source without a snapshot is reported as `FAILED` in replay mode. Snapshots are third-party content and are never
committed ([ADR-0004](docs/adr/0004-knowledge-base-ingested-from-source-registry.md)); copy them out with
`docker compose cp app:/app/snapshots ./snapshots` if you want them on the host. Locally (`./mvnw spring-boot:run`)
the directory is the git-ignored `snapshots/` at the repository root.

## API walkthrough

All endpoints are under `/api/v1`. Conversation and Turn endpoints identify the Expert by the `X-Expert-Id` header
(a stand-in for SSO; missing means `401`, someone else's Conversation or Turn means `404`). Knowledge Owner endpoints
(`/sources`, `/ingestion/runs`, `/feedback`) have no `X-Expert-Id` ownership filter in the PoC and sit behind
role-based authorization in the production NestJS BFF. Errors are RFC 9457 problem details. In Swagger UI, fill in
`X-Expert-Id` on Expert calls.

| Call | Purpose |
|---|---|
| `GET /product-categories` | the filter codes: `LAKASHITEL`, `SZEMELYI_KOLCSON`, `BANKSZAMLA` |
| `GET /sources?productCategory=` | Sources with their Current Version and last fetch |
| `GET /sources/{id}/versions` | a Source's version history |
| `GET /source-versions/{id}` | a Source Version's extracted text and Passages |
| `POST /ingestion/runs?sourceKey=` | ingest all Sources (or one) now; returns the run report |
| `GET /ingestion/runs/{id}` | an earlier run report |
| `GET /conversations?limit=` | list the Expert's Conversations, most recently active first (`limit` 1..200, default 50) |
| `POST /conversations` `{"productCategory": "LAKASHITEL"}` | start a Conversation (body optional; the category is its default filter) |
| `POST /conversations/{id}/turns` `{"question": "...", "productCategory": "..."}` | ask; `productCategory` overrides the filter for this Turn only |
| `GET /conversations/{id}` | reopen a Conversation with all Turns, Answers and Citations |
| `GET /turns/{id}/audit` | the Turn's audit record (question rewrite, retrieval, model, prompt version, raw output, verdict) |
| `POST /turns/{id}/feedback` `{"rating": "INCORRECT", "comment": "..."}` | rate an Answer: `HELPFUL`, `INCORRECT`, `OUTDATED` |
| `GET /feedback?rating=&productCategory=&limit=` | Knowledge Owner feedback queue, newest first across all Experts, with Turn context and cited Source Versions |

A Turn looks like this (abridged):

```json
{
  "id": 2, "conversationId": 2, "ordinal": 1,
  "question": "Hány évig ingyenes a CIB ECO Plusz bankszámla?",
  "standaloneQuestion": "Hány évig ingyenes a CIB ECO Plusz bankszámla?",
  "productCategory": null, "status": "ANSWERED", "refusalReason": null,
  "answer": "5 évig ingyenes a CIB Mastercard Gold bankkártyád is, nem csak az első évben. [4]",
  "citations": [{
    "marker": 4, "sourceId": 3, "sourceVersionId": 3, "versionNumber": 1, "versionStatus": "CURRENT",
    "currentVersionId": null, "sourceStatus": "ACTIVE", "sourceTitle": "CIB ECO Plusz ingyenes bankszámla",
    "url": "https://bankmonitor.hu/bankszamla/cib-eco-plusz/", "bank": "CIB Bank", "bankProduct": "CIB ECO Plusz",
    "productCategory": "BANKSZAMLA", "fetchedAt": "...", "passageId": 22,
    "passageHeadingPath": ["..."], "passageText": "..."
  }],
  "relevantSources": [], "latencyMs": 41, "chatProvider": "offline", "chatModel": "extractive-v1"
}
```

`status` is `ANSWERED` or `REFUSED`; `refusalReason` is `NO_EVIDENCE` (nothing relevant retrieved, the model is not
called), `MODEL_DECLINED` (the model said the Passages do not answer the Question) or `VERIFICATION_FAILED` (the
Answer did not pass the checks). The last two list the most relevant Sources in `relevantSources`. When a cited
Source Version has since been superseded, `versionStatus` is `SUPERSEDED` and `currentVersionId` points to the new one.
When the cited Source has since been dropped from the Source Registry, `sourceStatus` is `RETIRED`.

The step-by-step script with sample Hungarian Questions and expected behavior is in
[docs/smoke-test.md](docs/smoke-test.md).

## Development

Requirements: JDK 21 and Docker (for Postgres and Testcontainers).

```bash
./mvnw spring-boot:run      # starts the postgres service of compose.yaml automatically, then the service on :8080
./mvnw clean verify         # HTTP-level integration tests (*IT, failsafe, Testcontainers)
```

To run the service outside Docker with the same settings as the compose `app` service (snapshot recording,
LM Studio URLs and models), activate the `local` profile
([application-local.yaml](src/main/resources/application-local.yaml)): `./mvnw spring-boot:run
-Dspring-boot.run.profiles=local`, or `SPRING_PROFILES_ACTIVE=local` in the IDE run configuration. The same
environment variables as in compose.yaml override it, e.g. `CHAT_PROVIDER=openai-compatible`.

Tests never touch bankmonitor.hu or a real model: integration tests run against Postgres in Testcontainers, an
in-process fixture web site, and a scripted chat model; the real providers are tested against faked HTTP APIs.

Code is organised by feature under `com.epfisztner.knowledgeassistant` (`source`, `ingestion`, `retrieval`,
`embedding`, `rewrite`, `generation`, `verification`, `conversation`, `provider`, ...). Database migrations are in
`src/main/resources/db/migration`.
