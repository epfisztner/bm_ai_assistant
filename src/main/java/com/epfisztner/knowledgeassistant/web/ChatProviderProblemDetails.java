package com.epfisztner.knowledgeassistant.web;

import com.epfisztner.knowledgeassistant.generation.ChatProviderException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * Translates upstream chat-provider failures ({@link ChatProviderException}) into sanitized
 * RFC 9457 gateway Problem Details ({@code 502 Bad Gateway} or {@code 504 Gateway Timeout}),
 * logging full provider diagnostics server-side.
 */
@RestControllerAdvice
class ChatProviderProblemDetails {

    private static final Logger log = LoggerFactory.getLogger(ChatProviderProblemDetails.class);

    @ExceptionHandler(ChatProviderException.class)
    ResponseEntity<ProblemDetail> handleChatProviderException(ChatProviderException ex, HttpServletRequest request) {
        log.error("Upstream chat provider {} (model={}) {}",
                ex.provider(), ex.model(), ex.isTimeout() ? "timed out" : "failed", ex);
        HttpStatus status = ex.isTimeout() ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY;
        String detail = ex.isTimeout()
                ? "The upstream chat provider did not respond in time."
                : "The upstream chat provider failed to process the request.";
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setInstance(URI.create(request.getRequestURI()));
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }
}
