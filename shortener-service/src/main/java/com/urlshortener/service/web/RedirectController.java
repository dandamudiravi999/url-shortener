package com.urlshortener.service.web;

import com.urlshortener.service.service.ShortUrlService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@Tag(name = "Redirect")
public class RedirectController {

    private final ShortUrlService service;

    public RedirectController(ShortUrlService service) {
        this.service = service;
    }

    /**
     * 302 (not 301) plus no-store: browsers must come back to us on every click,
     * otherwise analytics undercount and deletes/expiry would not take effect for
     * users who already visited the link.
     */
    @GetMapping("/{code:[A-Za-z0-9_-]{3,32}}")
    @Operation(summary = "Follow a short link", description = "302 to the original URL; 404 if unknown, 410 if expired.")
    public ResponseEntity<Void> redirect(@PathVariable String code,
                                         @RequestHeader(value = HttpHeaders.REFERER, required = false) String referrer,
                                         @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        String target = service.resolveForRedirect(code, referrer, userAgent);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(target))
                .cacheControl(CacheControl.noStore())
                .build();
    }
}
