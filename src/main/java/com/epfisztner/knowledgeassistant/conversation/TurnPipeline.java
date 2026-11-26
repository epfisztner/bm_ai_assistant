package com.epfisztner.knowledgeassistant.conversation;

import com.epfisztner.knowledgeassistant.conversation.Conversations.Conversation;
import com.epfisztner.knowledgeassistant.conversation.Conversations.NewCitation;
import com.epfisztner.knowledgeassistant.conversation.Conversations.NewTurn;
import com.epfisztner.knowledgeassistant.conversation.Conversations.RefusalReason;
import com.epfisztner.knowledgeassistant.conversation.Conversations.TurnStatus;
import com.epfisztner.knowledgeassistant.conversation.Conversations.TurnView;
import com.epfisztner.knowledgeassistant.generation.AnswerGenerator;
import com.epfisztner.knowledgeassistant.generation.AnswerGenerator.Generation;
import com.epfisztner.knowledgeassistant.conversation.TurnAudits.NewTurnAudit;
import com.epfisztner.knowledgeassistant.generation.ChatModelPort;
import com.epfisztner.knowledgeassistant.generation.ChatModelPort.ChatReply;
import com.epfisztner.knowledgeassistant.generation.GroundedPrompt;
import com.epfisztner.knowledgeassistant.generation.GroundedPrompt.PresentedPassage;
import com.epfisztner.knowledgeassistant.productcategory.ProductCategory;
import com.epfisztner.knowledgeassistant.retrieval.Retrieval;
import com.epfisztner.knowledgeassistant.retrieval.RetrievalQuery;
import com.epfisztner.knowledgeassistant.retrieval.RetrievedPassage;
import com.epfisztner.knowledgeassistant.retrieval.Retriever;
import com.epfisztner.knowledgeassistant.rewrite.QuestionRewriter;
import com.epfisztner.knowledgeassistant.rewrite.QuestionRewriter.Rewrite;
import com.epfisztner.knowledgeassistant.verification.AnswerVerifier;
import com.epfisztner.knowledgeassistant.verification.AnswerVerifier.Verdict;
import com.epfisztner.knowledgeassistant.verification.CitationMarkers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * One Turn, end to end: rewrite → retrieve → generate → verify → persist. Each stage sits behind
 * its own seam ({@link QuestionRewriter}, {@link Retriever}, {@link AnswerGenerator} with its
 * chat model port, {@link AnswerVerifier}); this class only sequences them and decides the
 * Turn's status.
 */
@Component
class TurnPipeline {

    static final String NO_EVIDENCE_TEXT =
            "A tudásbázis nem tartalmaz elegendő bizonyítékot a kérdés megválaszolásához.";
    static final String VERIFICATION_FAILED_TEXT =
            "A választ nem sikerült a forrásokkal ellenőrizni, ezért nem adunk választ. Kérjük, nézze meg a releváns forrásokat.";

    private final QuestionRewriter rewriter;
    private final Retriever retriever;
    private final AnswerGenerator generator;
    private final AnswerVerifier verifier;
    private final ChatModelPort chatModel;
    private final Conversations conversations;
    private final TurnAudits audits;
    private final TransactionTemplate transactions;
    private final int historyTurns;
    private final int refusalSources;

    TurnPipeline(QuestionRewriter rewriter, Retriever retriever, AnswerGenerator generator, AnswerVerifier verifier,
                 ChatModelPort chatModel, Conversations conversations, TurnAudits audits,
                 PlatformTransactionManager transactionManager,
                 @Value("${knowledge.conversation.history-turns:3}") int historyTurns,
                 @Value("${knowledge.conversation.refusal-sources:3}") int refusalSources) {
        this.rewriter = rewriter;
        this.chatModel = chatModel;
        this.retriever = retriever;
        this.generator = generator;
        this.verifier = verifier;
        this.conversations = conversations;
        this.audits = audits;
        this.transactions = new TransactionTemplate(transactionManager);
        this.historyTurns = historyTurns;
        this.refusalSources = refusalSources;
    }

    TurnView ask(Conversation conversation, String question, Optional<ProductCategory> productCategoryOverride) {
        long started = System.nanoTime();
        Optional<ProductCategory> productCategory =
                productCategoryOverride.or(() -> Optional.ofNullable(conversation.productCategory()));

        Rewrite rewrite = rewriter.rewrite(question, conversations.recentTurns(conversation.id(), historyTurns));
        String standalone = rewrite.standaloneQuestion();
        Retrieval retrieval = retriever.retrieve(new RetrievalQuery(standalone, productCategory));
        List<RetrievedPassage> passages = retrieval.presented();

        TurnOutcome outcome = passages.isEmpty()
                ? TurnOutcome.noEvidence()
                : generateAndVerify(standalone, passages);

        long latencyMs = (System.nanoTime() - started) / 1_000_000;
        ChatReply chatModelUsed = chatModelUsed(outcome);
        NewTurn turn = new NewTurn(question, standalone, productCategory, outcome.status(), outcome.reason(),
                outcome.text(), latencyMs, outcome.citations(), outcome.relevantSources(), chatModelUsed.provider(),
                chatModelUsed.model());
        return transactions.execute(status -> {
            TurnView stored = conversations.addTurn(conversation.id(), turn);
            audits.record(stored.id(), audit(rewrite, retrieval, outcome));
            return stored;
        });
    }

    /**
     * The audit record: the rewrite step, everything retrieved, the prompt version and raw model
     * output, and the verdict (the answering chat provider/model are stored on the Turn itself).
     */
    private static NewTurnAudit audit(Rewrite rewrite, Retrieval retrieval, TurnOutcome outcome) {
        Generation generation = outcome.generation();
        Verdict verdict = outcome.verdict();
        Map<Long, Integer> markers = generation == null ? Map.of() : generation.prompt().passages().stream()
                .collect(Collectors.toMap(p -> p.passage().passageId(), PresentedPassage::marker, (a, b) -> a));
        return new NewTurnAudit(rewrite, retrieval, markers,
                generation == null ? null : generation.prompt().promptVersion(),
                generation == null ? null : generation.reply().text(),
                verdict == null ? null : verdict.passed(),
                verdict == null ? null : verdict.failedChecks());
    }

    /**
     * The provider and model that generated the Answer, as reported with its reply; when the model
     * was not called (NO_EVIDENCE), the configured ones.
     */
    private ChatReply chatModelUsed(TurnOutcome outcome) {
        return outcome.generation() != null
                ? outcome.generation().reply()
                : new ChatReply(null, chatModel.provider(), chatModel.model());
    }

    /**
     * Generates and verifies an Answer. Only verified output reaches the Expert; on a Refusal the
     * raw model output and failed checks stay in the outcome (for the audit record), and the
     * Expert gets a fixed Refusal text plus the most relevant Sources.
     */
    private TurnOutcome generateAndVerify(String standalone, List<RetrievedPassage> passages) {
        Generation generation = generator.generate(standalone, passages);
        if (generation.declined()) {
            return TurnOutcome.refused(RefusalReason.MODEL_DECLINED, NO_EVIDENCE_TEXT, relevantSources(passages),
                    generation, null);
        }
        String output = generation.reply().text();
        Verdict verdict = verifier.verify(output, generation.prompt());
        if (!verdict.passed()) {
            return TurnOutcome.refused(RefusalReason.VERIFICATION_FAILED, VERIFICATION_FAILED_TEXT,
                    relevantSources(passages), generation, verdict);
        }
        return TurnOutcome.answered(output, citations(output, generation.prompt()), generation, verdict);
    }

    /**
     * The best-ranked Passage of each of the top retrieved Sources, best first.
     */
    private List<RetrievedPassage> relevantSources(List<RetrievedPassage> passages) {
        Map<Long, RetrievedPassage> bySource = new LinkedHashMap<>();
        passages.forEach(p -> bySource.putIfAbsent(p.sourceId(), p));
        return bySource.values().stream().limit(refusalSources).toList();
    }

    /**
     * One Citation per distinct marker in the Answer that refers to a presented Passage.
     */
    private static List<NewCitation> citations(String answer, GroundedPrompt prompt) {
        return CitationMarkers.in(answer).stream()
                .flatMap(marker -> prompt.passage(marker).stream())
                .map(p -> new NewCitation(p.marker(), p.passage()))
                .toList();
    }

    /**
     * How the Turn ended. {@code generation} (prompt + raw model output) is null when the model
     * was not called; {@code verdict} (with failed checks) is null when nothing was verified.
     */
    private record TurnOutcome(TurnStatus status, RefusalReason reason, String text, List<NewCitation> citations,
                               List<RetrievedPassage> relevantSources, Generation generation, Verdict verdict) {

        static TurnOutcome answered(String text, List<NewCitation> citations, Generation generation, Verdict verdict) {
            return new TurnOutcome(TurnStatus.ANSWERED, null, text, citations, List.of(), generation, verdict);
        }

        static TurnOutcome noEvidence() {
            return new TurnOutcome(TurnStatus.REFUSED, RefusalReason.NO_EVIDENCE, NO_EVIDENCE_TEXT, List.of(),
                    List.of(), null, null);
        }

        static TurnOutcome refused(RefusalReason reason, String text, List<RetrievedPassage> relevantSources,
                                   Generation generation, Verdict verdict) {
            return new TurnOutcome(TurnStatus.REFUSED, reason, text, List.of(), relevantSources, generation, verdict);
        }
    }
}
