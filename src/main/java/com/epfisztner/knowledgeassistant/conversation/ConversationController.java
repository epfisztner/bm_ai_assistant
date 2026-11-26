package com.epfisztner.knowledgeassistant.conversation;

import com.epfisztner.knowledgeassistant.conversation.Conversations.Conversation;
import com.epfisztner.knowledgeassistant.conversation.Conversations.ConversationSummary;
import com.epfisztner.knowledgeassistant.conversation.Conversations.TurnView;
import com.epfisztner.knowledgeassistant.expert.ExpertId;
import com.epfisztner.knowledgeassistant.productcategory.ProductCategory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/conversations")
@Tag(name = "Conversations")
class ConversationController {

    /**
     * @param productCategory the Conversation's default filter; absent or null = search every Product Category
     */
    record StartConversationRequest(ProductCategory productCategory) {
    }

    /**
     * @param productCategory overrides the Conversation's default filter for this Turn only
     */
    record AskRequest(@NotBlank @Size(max = 2000) String question, ProductCategory productCategory) {
    }

    /**
     * A Conversation with all its Turns, oldest first.
     */
    record ConversationHistory(long id, String expertId, ProductCategory productCategory, OffsetDateTime createdAt,
                               List<TurnView> turns) {
    }

    private final Conversations conversations;
    private final TurnPipeline pipeline;

    ConversationController(Conversations conversations, TurnPipeline pipeline) {
        this.conversations = conversations;
        this.pipeline = pipeline;
    }

    @GetMapping
    @Operation(summary = "List the Expert's Conversations, most recently active first")
    List<ConversationSummary> list(@Parameter(in = ParameterIn.HEADER, name = ExpertId.HEADER, required = true) ExpertId expert,
                                   @Parameter(description = "Maximum number of Conversations to return (1..200)")
                                   @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return conversations.list(expert.value(), limit);
    }

    @PostMapping
    @Operation(summary = "Start a Conversation")
    ResponseEntity<Conversation> start(@Parameter(in = ParameterIn.HEADER, name = ExpertId.HEADER, required = true) ExpertId expert,
                                       @RequestBody(required = false) StartConversationRequest request) {
        Conversation conversation = conversations.start(expert.value(),
                Optional.ofNullable(request).map(StartConversationRequest::productCategory));
        var location = ServletUriComponentsBuilder.fromCurrentRequestUri()
                .path("/{id}").buildAndExpand(conversation.id()).toUri();
        return ResponseEntity.created(location).body(conversation);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Reopen a Conversation: all its Turns in order, with Answers and Citations")
    ConversationHistory history(@Parameter(in = ParameterIn.HEADER, name = ExpertId.HEADER, required = true) ExpertId expert,
                                @PathVariable long id) {
        Conversation conversation = owned(id, expert);
        return new ConversationHistory(conversation.id(), conversation.expertId(), conversation.productCategory(),
                conversation.createdAt(), conversations.turns(conversation.id()));
    }

    @PostMapping("/{id}/turns")
    @Operation(summary = "Ask a Question; returns the Turn with its Answer and Citations, or a Refusal")
    ResponseEntity<TurnView> ask(@Parameter(in = ParameterIn.HEADER, name = ExpertId.HEADER, required = true) ExpertId expert,
                                 @PathVariable long id, @Valid @RequestBody AskRequest request) {
        Conversation conversation = owned(id, expert);
        TurnView turn = pipeline.ask(conversation, request.question().strip(),
                Optional.ofNullable(request.productCategory()));
        return ResponseEntity.status(HttpStatus.CREATED).body(turn);
    }

    private Conversation owned(long id, ExpertId expert) {
        return conversations.find(id, expert.value())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No Conversation with id " + id));
    }
}
