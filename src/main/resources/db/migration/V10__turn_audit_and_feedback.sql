-- Turn audit: how each Turn was produced, for Compliance (one row per Turn, every status); the
-- chat provider/model are on the turn row (V9). Prompt columns are null when the chat model was
-- not called (NO_EVIDENCE); the verdict columns are null when nothing was verified
-- (NO_EVIDENCE, MODEL_DECLINED).
CREATE TABLE turn_audit (
    turn_id          bigint PRIMARY KEY REFERENCES turn (id),
    -- embedding model of the semantic retrieval half; null = keyword-only retrieval
    embedding_model  text,
    prompt_version   text,
    raw_model_output text,
    verdict_passed   boolean,
    failed_checks    text[],
    CHECK ((verdict_passed IS NULL) = (failed_checks IS NULL))
);

-- Every retrieval candidate of the Turn (fused order), whether or not it went into the prompt,
-- with a snapshot of the Source metadata as retrieved.
CREATE TABLE turn_audit_passage (
    turn_id              bigint           NOT NULL REFERENCES turn (id),
    rank                 integer          NOT NULL,
    passage_id           bigint           NOT NULL REFERENCES passage (id),
    source_version_id    bigint           NOT NULL REFERENCES source_version (id),
    source_id            bigint           NOT NULL REFERENCES source (id),
    version_number       integer          NOT NULL,
    source_title         text             NOT NULL,
    bank_product         text             NOT NULL,
    passage_heading_path text[]           NOT NULL,
    lexical_rank         integer,
    vector_rank          integer,
    score                double precision NOT NULL,
    in_prompt            boolean          NOT NULL,
    -- the Passage's [n] marker in the prompt; null when not presented
    marker               integer,
    PRIMARY KEY (turn_id, rank)
);

-- An Expert's rating of a Turn; the Turn's Citations link it to the exact Source Versions.
CREATE TABLE feedback (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    turn_id    bigint      NOT NULL REFERENCES turn (id),
    expert_id  text        NOT NULL,
    rating     text        NOT NULL CHECK (rating IN ('HELPFUL', 'INCORRECT', 'OUTDATED')),
    comment    text,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX feedback_turn_idx ON feedback (turn_id);
