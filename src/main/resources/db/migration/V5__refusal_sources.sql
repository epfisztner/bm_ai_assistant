-- The most relevant Sources listed with a Refusal (MODEL_DECLINED, VERIFICATION_FAILED), so the
-- Expert can read them; snapshot of the Source metadata as retrieved in the Turn.
CREATE TABLE turn_relevant_source (
    turn_id           bigint  NOT NULL REFERENCES turn (id),
    rank              integer NOT NULL,
    source_id         bigint  NOT NULL REFERENCES source (id),
    source_version_id bigint  NOT NULL REFERENCES source_version (id),
    version_number    integer NOT NULL,
    source_title      text    NOT NULL,
    url               text    NOT NULL,
    bank              text    NOT NULL,
    bank_product      text    NOT NULL,
    product_category  text    NOT NULL,
    PRIMARY KEY (turn_id, rank)
);
