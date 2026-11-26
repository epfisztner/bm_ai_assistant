package com.epfisztner.knowledgeassistant.verification;

import com.epfisztner.knowledgeassistant.generation.GroundedPrompt;

import java.util.List;

/**
 * Checks the model's output against the Passages presented in this Turn before the Expert sees
 * it (ADR-0002). A failed verdict turns the Turn into a {@code VERIFICATION_FAILED} Refusal.
 */
public interface AnswerVerifier {

    Verdict verify(String modelOutput, GroundedPrompt prompt);

    /**
     * Pass, or fail with the list of failed checks (human-readable, for the audit record).
     */
    record Verdict(boolean passed, List<String> failedChecks) {

        public static Verdict pass() {
            return new Verdict(true, List.of());
        }

        public static Verdict fail(List<String> failedChecks) {
            return new Verdict(false, List.copyOf(failedChecks));
        }
    }
}
