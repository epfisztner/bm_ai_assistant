# Conversation history is never evidence

In a multi-turn Conversation, earlier Turns are used only to rewrite the Expert's follow-up into a Standalone Question; retrieval runs fresh on every Turn and an Answer may cite only Passages retrieved in its own Turn. Letting the model treat its own earlier Answers as facts would let a single unsupported statement propagate silently through the rest of the Conversation and would break Citation traceability to a Source Version.
