package com.epfisztner.knowledgeassistant.web;

import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.exc.MismatchedInputException;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * RFC 9457 problem details for every error of the API (Spring's standard handling), with the
 * validation errors spelled out: a 400 for an invalid request carries an {@code errors} array of
 * {@code {field, message}} naming each offending field, whether the body failed bean validation
 * or could not be bound (e.g. an unknown enum code).
 */
@RestControllerAdvice
class ValidationProblemDetails extends ResponseEntityExceptionHandler {

    /**
     * One invalid request field (a JSON property path such as {@code rating}, or a parameter name).
     */
    record FieldError(String field, String message) {
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        List<FieldError> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldError(error.getField(), error.getDefaultMessage()))
                .toList();
        return problem(ex, ex.getBody(), "The request is invalid.", errors, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                            HttpHeaders headers, HttpStatusCode status,
                                                                            WebRequest request) {
        List<FieldError> errors = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new FieldError(result.getMethodParameter().getParameterName(),
                                error.getDefaultMessage())))
                .toList();
        return problem(ex, ex.getBody(), "The request is invalid.", errors, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        ProblemDetail body = ProblemDetail.forStatus(status);
        if (ex.getCause() instanceof MismatchedInputException mismatch && !mismatch.getPath().isEmpty()) {
            FieldError error = new FieldError(path(mismatch), message(mismatch));
            return problem(ex, body, "The request body has an invalid value.", List.of(error), headers, status,
                    request);
        }
        return problem(ex, body, "The request body is missing or is not valid JSON.", List.of(), headers, status,
                request);
    }

    /**
     * A query or path parameter that cannot be converted, e.g. an unknown Product Category code.
     */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
                                                        HttpStatusCode status, WebRequest request) {
        ProblemDetail body = ProblemDetail.forStatus(status);
        String field = ex.getPropertyName();
        Class<?> target = ex.getRequiredType();
        if (ex instanceof MethodArgumentTypeMismatchException mismatch) {
            field = mismatch.getName();
            target = mismatch.getParameter().nestedIfOptional().getNestedParameterType();
        }
        FieldError error = new FieldError(field, message(ex.getValue(), target));
        return problem(ex, body, "The request has an invalid parameter value.", List.of(error), headers, status,
                request);
    }

    private ResponseEntity<Object> problem(Exception ex, ProblemDetail body, String detail, List<FieldError> errors,
                                           HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        body.setDetail(detail);
        body.setProperty("errors", errors);
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    /**
     * Every problem names the request path it occurred on as its {@code instance}.
     */
    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers, HttpStatusCode status,
                                                          WebRequest request) {
        if (body instanceof ProblemDetail problem && problem.getInstance() == null
                && request instanceof ServletWebRequest servlet) {
            problem.setInstance(URI.create(servlet.getRequest().getRequestURI()));
        }
        return super.createResponseEntity(body, headers, status, request);
    }

    /**
     * E.g. {@code rating}, {@code items[2].name}.
     */
    private static String path(JacksonException exception) {
        StringBuilder path = new StringBuilder();
        for (JacksonException.Reference reference : exception.getPath()) {
            if (reference.getPropertyName() != null) {
                path.append(path.isEmpty() ? "" : ".").append(reference.getPropertyName());
            } else if (reference.getIndex() >= 0) {
                path.append('[').append(reference.getIndex()).append(']');
            }
        }
        return path.toString();
    }

    private static String message(MismatchedInputException mismatch) {
        Object value = mismatch instanceof InvalidFormatException invalid ? invalid.getValue() : null;
        return message(value, mismatch.getTargetType());
    }

    /**
     * Lists the allowed codes when the target is an enum.
     */
    private static String message(Object value, Class<?> target) {
        if (value != null && target != null && target.isEnum()) {
            String allowed = Arrays.stream(target.getEnumConstants()).map(String::valueOf)
                    .collect(Collectors.joining(", "));
            return "'" + value + "' is not one of: " + allowed;
        }
        return target == null ? "has an invalid value" : "must be a valid " + target.getSimpleName();
    }
}
