# Answers are verified before release; no token streaming

Every generated Answer passes a deterministic verifier before the Expert sees it: each paragraph must carry a Citation, every cited Passage must have been retrieved in the same Turn, and every number, amount, percentage and duration must appear verbatim in the cited Passages. A failed check turns the Answer into a Refusal that still lists the relevant Sources. Because nothing unverified may reach the Expert, Answers are returned whole rather than streamed token by token; we accept the extra perceived latency in exchange for the business's core requirement that wrong information must not go out.

## Consequences

- Do not "improve UX" by adding streaming of raw model output; stream only progress events (e.g. "searching", "verifying") if latency becomes a problem.
- The verifier is deliberately strict and will produce some false Refusals; these are tracked via Expert feedback and the evaluation set rather than by loosening the check.
