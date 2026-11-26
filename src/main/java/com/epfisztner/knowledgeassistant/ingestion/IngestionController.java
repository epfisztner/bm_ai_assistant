package com.epfisztner.knowledgeassistant.ingestion;

import com.epfisztner.knowledgeassistant.ingestion.IngestionRuns.IngestionRun;
import com.epfisztner.knowledgeassistant.ingestion.IngestionRuns.Trigger;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.Optional;

@RestController
@RequestMapping("/api/v1/ingestion/runs")
@Tag(name = "Ingestion", description = "Knowledge Owner endpoints for running and inspecting Knowledge Base ingestion. No X-Expert-Id ownership filter in the PoC; role-gated in the production NestJS BFF.")
class IngestionController {

    private final Ingestion ingestion;
    private final IngestionRuns runs;

    IngestionController(Ingestion ingestion, IngestionRuns runs) {
        this.ingestion = ingestion;
        this.runs = runs;
    }

    @PostMapping
    @Operation(summary = "Fetch the Sources in the Source Registry (or one Source) now and return the run report")
    ResponseEntity<IngestionRun> run(
            @Parameter(description = "Ingest only the Source with this registry key")
            @RequestParam Optional<String> sourceKey) {
        IngestionRun run = ingestion.run(Trigger.MANUAL, sourceKey);
        var location = ServletUriComponentsBuilder.fromCurrentRequestUri().replaceQuery(null)
                .path("/{id}").buildAndExpand(run.id()).toUri();
        return ResponseEntity.created(location).body(run);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Return the report of an earlier ingestion run")
    IngestionRun get(@PathVariable long id) {
        return runs.find(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No ingestion run with id " + id));
    }

    @ExceptionHandler(Ingestion.UnknownSourceException.class)
    ProblemDetail unknownSource(Ingestion.UnknownSourceException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }
}
