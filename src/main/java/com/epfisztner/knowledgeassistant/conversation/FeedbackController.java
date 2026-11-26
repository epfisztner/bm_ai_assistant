package com.epfisztner.knowledgeassistant.conversation;

import com.epfisztner.knowledgeassistant.conversation.Feedbacks.FeedbackQueueItem;
import com.epfisztner.knowledgeassistant.conversation.Feedbacks.Rating;
import com.epfisztner.knowledgeassistant.productcategory.ProductCategory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

/**
 * Knowledge Owner feedback triage queue. No {@code X-Expert-Id} ownership filter in the PoC;
 * role-gated in the production NestJS BFF.
 */
@RestController
@RequestMapping("/api/v1/feedback")
@Tag(name = "Feedback", description = "Knowledge Owner feedback triage queue. No X-Expert-Id ownership filter in the PoC; role-gated in the production NestJS BFF.")
class FeedbackController {

    private final Feedbacks feedbacks;

    FeedbackController(Feedbacks feedbacks) {
        this.feedbacks = feedbacks;
    }

    @GetMapping
    @Operation(
            summary = "List submitted feedback newest first for Knowledge Owner triage",
            description = "Knowledge Owner endpoint returning feedback across all Experts with Turn context and cited Source Versions. No X-Expert-Id ownership filter in the PoC; sits behind role-based authorization in the production NestJS BFF.")
    List<FeedbackQueueItem> list(
            @Parameter(description = "Filter by feedback rating")
            @RequestParam Optional<Rating> rating,
            @Parameter(description = "Filter by the Turn's effective Product Category")
            @RequestParam Optional<ProductCategory> productCategory,
            @Parameter(description = "Maximum number of feedback items to return (1–200)")
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return feedbacks.list(rating, productCategory, limit);
    }
}
