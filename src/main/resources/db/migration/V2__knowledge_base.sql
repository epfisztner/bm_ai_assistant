-- Knowledge Base: Sources, their immutable Source Versions, Passages, and ingestion runs.

-- Hungarian full-text search made accent-insensitive: unaccent runs before the Hungarian stemmer,
-- so "szamlavezetesi" and "számlavezetési" produce the same lexemes (ADR-0001).
CREATE TEXT SEARCH CONFIGURATION hungarian_unaccent (COPY = hungarian);
ALTER TEXT SEARCH CONFIGURATION hungarian_unaccent
    ALTER MAPPING FOR hword, hword_part, word WITH unaccent, hungarian_stem;

CREATE TABLE source (
    id                    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    registry_key          text        NOT NULL UNIQUE,
    url                   text        NOT NULL,
    title_override        text,
    product_category      text        NOT NULL CHECK (product_category IN ('LAKASHITEL', 'SZEMELYI_KOLCSON', 'BANKSZAMLA')),
    bank                  text        NOT NULL,
    bank_product          text        NOT NULL,
    status                text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'RETIRED')),
    last_fetch_attempt_at timestamptz,
    last_fetch_outcome    text CHECK (last_fetch_outcome IN ('UNCHANGED', 'NEW_VERSION', 'FAILED', 'RETIRED')),
    last_fetch_error      text,
    created_at            timestamptz NOT NULL DEFAULT now()
);

-- Immutable once written, except for the CURRENT -> SUPERSEDED transition.
CREATE TABLE source_version (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_id      bigint      NOT NULL REFERENCES source (id),
    version_number integer     NOT NULL,
    content_hash   text        NOT NULL,
    raw_content    text        NOT NULL,
    title          text        NOT NULL,
    extracted_text text        NOT NULL,
    fetched_at     timestamptz NOT NULL,
    status         text        NOT NULL CHECK (status IN ('CURRENT', 'SUPERSEDED')),
    superseded_at  timestamptz,
    UNIQUE (source_id, version_number)
);

-- Exactly one Current Version per Source; it is the Source's current version reference.
CREATE UNIQUE INDEX source_version_one_current_per_source ON source_version (source_id) WHERE status = 'CURRENT';

CREATE TABLE passage (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_version_id bigint   NOT NULL REFERENCES source_version (id),
    ordinal           integer  NOT NULL,
    heading_path      text[]   NOT NULL,
    text              text     NOT NULL,
    -- to_tsvector('hungarian_unaccent', heading path + text), written on insert
    search_vector     tsvector NOT NULL,
    -- filled by the embedding step; dimension is fixed by configuration (see later migrations)
    embedding         vector,
    embedding_model   text,
    UNIQUE (source_version_id, ordinal)
);

CREATE INDEX passage_search_vector_idx ON passage USING gin (search_vector);

CREATE TABLE ingestion_run (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    trigger     text        NOT NULL CHECK (trigger IN ('STARTUP', 'MANUAL', 'SCHEDULE')),
    source_key  text,
    started_at  timestamptz NOT NULL,
    finished_at timestamptz
);

CREATE TABLE ingestion_run_outcome (
    run_id            bigint  NOT NULL REFERENCES ingestion_run (id),
    ordinal           integer NOT NULL,
    source_key        text    NOT NULL,
    source_id         bigint REFERENCES source (id),
    outcome           text    NOT NULL CHECK (outcome IN ('UNCHANGED', 'NEW_VERSION', 'FAILED', 'RETIRED')),
    source_version_id bigint REFERENCES source_version (id),
    error             text,
    PRIMARY KEY (run_id, ordinal)
);
