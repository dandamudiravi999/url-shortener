package com.urlshortener.service.web;

import com.urlshortener.service.service.CreateResult;
import com.urlshortener.service.service.ShortUrlService;
import com.urlshortener.service.web.dto.CreateShortUrlRequest;
import com.urlshortener.service.web.dto.ShortUrlResponse;
import com.urlshortener.service.web.dto.UrlStatsResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/urls")
@Tag(name = "Short URLs", description = "Create, inspect and delete short links")
public class ShortUrlController {

    private final ShortUrlService service;
    private final ShortUrlMapper mapper;

    public ShortUrlController(ShortUrlService service, ShortUrlMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @PostMapping
    @Operation(summary = "Shorten a URL",
            description = "Returns 201 for a new link, or 200 with the existing link when the same permanent URL was already shortened.")
    @ApiResponse(responseCode = "201", description = "Created")
    @ApiResponse(responseCode = "200", description = "Existing link reused")
    @ApiResponse(responseCode = "400", description = "Invalid URL, alias or expiry")
    @ApiResponse(responseCode = "409", description = "Alias already taken")
    @ApiResponse(responseCode = "429", description = "Rate limit exceeded")
    public ResponseEntity<ShortUrlResponse> create(@Valid @RequestBody CreateShortUrlRequest request) {
        CreateResult result = service.create(request);
        ShortUrlResponse body = mapper.toResponse(result.shortUrl());
        if (!result.created()) {
            return ResponseEntity.ok(body);
        }
        return ResponseEntity.created(URI.create("/api/v1/urls/" + body.code())).body(body);
    }

    @GetMapping("/{code}")
    @Operation(summary = "Get link details")
    public ShortUrlResponse get(@PathVariable String code) {
        return mapper.toResponse(service.get(code));
    }

    @GetMapping("/{code}/stats")
    @Operation(summary = "Get click analytics", description = "Total clicks, daily breakdown for the recent window, top referrers.")
    public UrlStatsResponse stats(@PathVariable String code) {
        return service.stats(code);
    }

    @DeleteMapping("/{code}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a link", description = "Soft delete: redirects stop, the code is never reused.")
    public void delete(@PathVariable String code) {
        service.delete(code);
    }
}
