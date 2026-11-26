-- Conversations, Turns and Citations.
-- Later tickets add their own migrations; to avoid version clashes between parallel tickets,
-- ticket NN uses version VNN (e.g. V4 for ticket 04, V10 for ticket 10).

CREATE TABLE conversation (
    id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    expert_id        text        NOT NULL,
    -- default Product Category for the Conversation's Turns (optional)
    product_category text CHECK (product_category IN ('LAKASHITEL', 'SZEMELYI_KOLCSON', 'BANKSZAMLA')),
    created_at       timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX conversation_expert_idx ON conversation (expert_id);

CREATE TABLE turn (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    conversation_id     bigint      NOT NULL REFERENCES conversation (id),
    ordinal             integer     NOT NULL,
    question            text        NOT NULL,
    standalone_question text        NOT NULL,
    -- effective Product Category: per-Turn override, else Conversation default, else none
    product_category    text CHECK (product_category IN ('LAKASHITEL', 'SZEMELYI_KOLCSON', 'BANKSZAMLA')),
    status              text        NOT NULL CHECK (status IN ('ANSWERED', 'REFUSED')),
    refusal_reason      text CHECK (refusal_reason IN ('NO_EVIDENCE', 'MODEL_DECLINED', 'VERIFICATION_FAILED')),
    answer_text         text,
    latency_ms          bigint      NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (conversation_id, ordinal),
    CHECK ((status = 'REFUSED') = (refusal_reason IS NOT NULL))
);

-- A Citation points at the exact Passage and Source Version, plus a snapshot of the Passage
-- text and Source metadata as they were at answer time.
CREATE TABLE citation (
    turn_id               bigint      NOT NULL REFERENCES turn (id),
    marker                integer     NOT NULL,
    passage_id            bigint      NOT NULL REFERENCES passage (id),
    source_version_id     bigint      NOT NULL REFERENCES source_version (id),
    source_id             bigint      NOT NULL REFERENCES source (id),
    version_number        integer     NOT NULL,
    source_title          text        NOT NULL,
    url                   text        NOT NULL,
    bank                  text        NOT NULL,
    bank_product          text        NOT NULL,
    product_category      text        NOT NULL,
    fetched_at            timestamptz NOT NULL,
    passage_heading_path  text[]      NOT NULL,
    passage_text          text        NOT NULL,
    PRIMARY KEY (turn_id, marker)
);
