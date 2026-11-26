-- Which chat provider and model handled each Turn (for audit). For a Turn where the model was not
-- called (NO_EVIDENCE) it is the configured provider/model.
ALTER TABLE turn ADD COLUMN chat_provider text;
ALTER TABLE turn ADD COLUMN chat_model text;
