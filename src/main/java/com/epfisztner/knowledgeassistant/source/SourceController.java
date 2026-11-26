package com.epfisztner.knowledgeassistant.source;

import com.epfisztner.knowledgeassistant.productcategory.ProductCategory;
import com.epfisztner.knowledgeassistant.source.KnowledgeBase.PassageView;
import com.epfisztner.knowledgeassistant.source.KnowledgeBase.SourceView;
import com.epfisztner.knowledgeassistant.source.KnowledgeBase.VersionDetail;
import com.epfisztner.knowledgeassistant.source.KnowledgeBase.VersionStatus;
import com.epfisztner.knowledgeassistant.source.KnowledgeBase.VersionSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Knowledge Base", description = "Knowledge Owner endpoints for inspecting Sources and Source Versions. No X-Expert-Id ownership filter in the PoC; role-gated in the production NestJS BFF.")
class SourceController {

    record SourceVersionDetailResponse(long id, long sourceId, int versionNumber, VersionStatus status, String title,
                                       String contentHash, OffsetDateTime fetchedAt, OffsetDateTime supersededAt,
                                       String extractedText, List<PassageView> passages) {

        static SourceVersionDetailResponse of(VersionDetail detail) {
            VersionSummary v = detail.version();
            return new SourceVersionDetailResponse(v.id(), v.sourceId(), v.versionNumber(), v.status(), v.title(),
                    v.contentHash(), v.fetchedAt(), v.supersededAt(), detail.extractedText(), detail.passages());
        }
    }

    private final KnowledgeBase knowledgeBase;

    SourceController(KnowledgeBase knowledgeBase) {
        this.knowledgeBase = knowledgeBase;
    }

    @GetMapping("/sources")
    @Operation(summary = "List Sources with their Current Version and last fetch, optionally for one Product Category")
    List<SourceView> sources(@RequestParam Optional<ProductCategory> productCategory) {
        return knowledgeBase.sources(productCategory);
    }

    @GetMapping("/sources/{id}")
    @Operation(summary = "Return one Source")
    SourceView source(@PathVariable long id) {
        return knowledgeBase.source(id).orElseThrow(() -> notFound("Source", id));
    }

    @GetMapping("/sources/{id}/versions")
    @Operation(summary = "Return a Source's version history, newest first")
    List<VersionSummary> versions(@PathVariable long id) {
        if (knowledgeBase.source(id).isEmpty()) {
            throw notFound("Source", id);
        }
        return knowledgeBase.versions(id);
    }

    @GetMapping("/source-versions/{id}")
    @Operation(summary = "Return a Source Version with its extracted text and Passages")
    SourceVersionDetailResponse version(@PathVariable long id) {
        return knowledgeBase.version(id).map(SourceVersionDetailResponse::of)
                .orElseThrow(() -> notFound("Source Version", id));
    }

    private static ResponseStatusException notFound(String what, long id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "No " + what + " with id " + id);
    }
}
