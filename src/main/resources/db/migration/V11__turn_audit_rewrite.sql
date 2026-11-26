-- The question-rewrite step of a follow-up Turn: prompt version, the model's raw reply, which
-- provider/model produced it, and whether a blank reply made the service join the previous
-- Standalone Question with the Question instead. All null for a first Turn (no rewrite).
ALTER TABLE turn_audit
    ADD COLUMN rewrite_prompt_version text,
    ADD COLUMN rewrite_raw_output     text,
    ADD COLUMN rewrite_chat_provider  text,
    ADD COLUMN rewrite_chat_model     text,
    ADD COLUMN rewrite_fell_back      boolean,
    ADD CHECK ((rewrite_prompt_version IS NULL) = (rewrite_fell_back IS NULL));
