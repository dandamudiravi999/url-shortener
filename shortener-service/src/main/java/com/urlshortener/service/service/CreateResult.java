package com.urlshortener.service.service;

import com.urlshortener.service.domain.ShortUrl;

/** Outcome of a create call; {@code created=false} means an existing link was reused. */
public record CreateResult(ShortUrl shortUrl, boolean created) {
}
