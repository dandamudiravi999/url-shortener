package com.urlshortener.service.web;

import com.urlshortener.service.service.AliasConflictException;
import com.urlshortener.service.service.CodeGenerationException;
import com.urlshortener.service.service.InvalidRequestException;
import com.urlshortener.service.service.UrlExpiredException;
import com.urlshortener.service.service.UrlNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.stream.Collectors;

/** Maps domain errors to RFC 7807 problem+json responses with stable titles clients can rely on. */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(UrlNotFoundException.class)
    ProblemDetail notFound(UrlNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Short URL not found", e.getMessage());
    }

    @ExceptionHandler(UrlExpiredException.class)
    ProblemDetail expired(UrlExpiredException e) {
        return problem(HttpStatus.GONE, "Short URL expired", e.getMessage());
    }

    @ExceptionHandler(AliasConflictException.class)
    ProblemDetail conflict(AliasConflictException e) {
        return problem(HttpStatus.CONFLICT, "Alias already in use", e.getMessage());
    }

    @ExceptionHandler(InvalidRequestException.class)
    ProblemDetail invalid(InvalidRequestException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", e.getMessage());
    }

    @ExceptionHandler(CodeGenerationException.class)
    ProblemDetail exhausted(CodeGenerationException e) {
        log.error("Code generation exhausted: {}", e.getMessage());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Temporarily unable to allocate a code", e.getMessage());
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(problem(HttpStatus.BAD_REQUEST, "Invalid request", detail));
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(title);
        return pd;
    }
}
