# Expert Knowledge Assistant

An assistant that answers Experts' questions strictly from the central Knowledge Base, always showing which Source Version each statement came from.

## Language

### People

**Expert**:
An internal advisor who asks the assistant questions and relays the information to customers; remains responsible for what is told to the customer.
_Avoid_: User, agent, operator

**Knowledge Owner**:
The person responsible for which Sources are in the Knowledge Base and for their content being correct and current.
_Avoid_: Admin, content manager, editor

### Knowledge

**Knowledge Base**:
The curated set of Sources the assistant is allowed to answer from; nothing outside it counts as knowledge.
_Avoid_: Corpus, training data, the internet

**Source**:
A single piece of knowledge material with a stable identity (e.g. one product page), independent of how its content changes over time.
_Avoid_: File, page, article

**Source Version**:
An immutable snapshot of a Source's content as it was at a point in time; a Source has exactly one Current Version and any number of Superseded Versions.
_Avoid_: Revision, copy

**Passage**:
A contiguous section of one Source Version that is retrieved and cited as a unit.
_Avoid_: Chunk, snippet, fragment

**Source Registry**:
The declared list of Sources the Knowledge Base is built from, with each Source's Product Category, bank and Bank Product.
_Avoid_: Config, seed list

**Product Category**:
The business line a Source belongs to: lakáshitel (mortgage), személyi kölcsön (personal loan) or bankszámla (bank account).
_Avoid_: Product (ambiguous with Bank Product), type

**Bank Product**:
A specific offering of a specific bank within a Product Category (e.g. CIB ECO Plusz bankszámla).
_Avoid_: Product, offer

### Answering

**Conversation**:
A sequence of Turns between one Expert and the assistant on one topic.
_Avoid_: Chat, session, thread

**Turn**:
One Question from the Expert and the assistant's Answer to it.
_Avoid_: Message, exchange

**Standalone Question**:
The Expert's Question rewritten, using earlier Turns, so that it can be understood without the rest of the Conversation.
_Avoid_: Condensed query, rewritten prompt

**Answer**:
The assistant's reply to a Question, composed only of statements backed by Citations.
_Avoid_: Response, completion

**Citation**:
A reference from a statement in an Answer to the exact Source Version passage that supports it.
_Avoid_: Reference, footnote, link

**Refusal**:
An Answer that states the Knowledge Base does not contain enough evidence to answer, instead of guessing.
_Avoid_: Error, fallback, "I don't know"
