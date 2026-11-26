package com.epfisztner.knowledgeassistant.conversation;

import com.epfisztner.knowledgeassistant.conversation.Conversations.CitationView;
import com.epfisztner.knowledgeassistant.conversation.Conversations.RefusalReason;
import com.epfisztner.knowledgeassistant.conversation.Conversations.TurnStatus;
import com.epfisztner.knowledgeassistant.conversation.Conversations.TurnView;
import com.epfisztner.knowledgeassistant.conversation.Feedbacks.FeedbackView;
import com.epfisztner.knowledgeassistant.conversation.Feedbacks.Rating;
import com.epfisztner.knowledgeassistant.conversation.TurnAudits.AuditRecord;
import com.epfisztner.knowledgeassistant.conversation.TurnAudits.AuditedPassage;
import com.epfisztner.knowledgeassistant.conversation.TurnAudits.RewriteView;
import com.epfisztner.knowledgeassistant.conversation.TurnAudits.VerdictView;
import com.epfisztner.knowledgeassistant.generation.ChatProvider;
import com.epfisztner.knowledgeassistant.expert.ExpertId;
import com.epfisztner.knowledgeassistant.productcategory.ProductCategory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * A Turn's audit record and the Experts' feedback on it; same {@code X-Expert-Id} ownership as Conversations.
 */
@RestController
@RequestMapping("/api/v1/turns")
@Tag(name = "Turns")
class TurnController {

    /**
     * Everything needed to reconstruct how the Turn was produced. When the chat model was not
     * called (NO_EVIDENCE) the chat provider/model are the configured ones and the prompt fields
     * are null; {@code verdict} is null when nothing was verified; {@code rewrite} is null when the
     * Question was not rewritten (a first Turn).
     */
    record TurnAudit(long turnId, long conversationId, String question, String standaloneQuestion,
                     RewriteView rewrite, ProductCategory productCategory, TurnStatus status,
                     RefusalReason refusalReason, String answer, List<CitationView> citations,
                     List<AuditedPassage> retrievedPassages, ChatProvider chatProvider, String chatModel,
                     String embeddingModel, String promptVersion, String rawModelOutput, VerdictView verdict,
                     long latencyMs, OffsetDateTime createdAt) {

        static TurnAudit of(TurnView turn, AuditRecord audit) {
            return new TurnAudit(turn.id(), turn.conversationId(), turn.question(), turn.standaloneQuestion(),
                    audit == null ? null : audit.rewrite(),
                    turn.productCategory(), turn.status(), turn.refusalReason(), turn.answer(), turn.citations(),
                    audit == null ? List.of() : audit.retrievedPassages(),
                    turn.chatProvider(), turn.chatModel(), audit == null ? null : audit.embeddingModel(), audit == null ? null : audit.promptVersion(),
                    audit == null ? null : audit.rawModelOutput(), audit == null ? null : audit.verdict(),
                    turn.latencyMs(), turn.createdAt());
        }
    }

    /**
     * @param comment optional free text for the Knowledge Owner
     */
    record FeedbackRequest(@NotNull Rating rating, @Size(max = 4000) String comment) {
    }

    private final Conversations conversations;
    private final TurnAudits audits;
    private final Feedbacks feedbacks;

    TurnController(Conversations conversations, TurnAudits audits, Feedbacks feedbacks) {
        this.conversations = conversations;
        this.audits = audits;
        this.feedbacks = feedbacks;
    }

    @PostMapping("/{id}/feedback")
    @Operation(summary = "Rate the Turn's Answer as helpful, incorrect or outdated, with an optional comment")
    ResponseEntity<FeedbackView> feedback(@Parameter(in = ParameterIn.HEADER, name = ExpertId.HEADER, required = true) ExpertId expert,
                                          @PathVariable long id, @Valid @RequestBody FeedbackRequest request) {
        TurnView turn = owned(id, expert);
        String comment = request.comment() == null || request.comment().isBlank() ? null : request.comment().strip();
        FeedbackView feedback = feedbacks.add(turn.id(), expert.value(), request.rating(), comment);
        return ResponseEntity.status(HttpStatus.CREATED).body(feedback);
    }

    @GetMapping("/{id}/audit")
    @Operation(summary = "The Turn's full audit record: retrieval, model, prompt version, raw output, verdict")
    TurnAudit audit(@Parameter(in = ParameterIn.HEADER, name = ExpertId.HEADER, required = true) ExpertId expert,
                    @PathVariable long id) {
        TurnView turn = owned(id, expert);
        return TurnAudit.of(turn, audits.find(turn.id()).orElse(null));
    }

    private TurnView owned(long id, ExpertId expert) {
        return conversations.ownedTurn(id, expert.value())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No Turn with id " + id));
    }
}
