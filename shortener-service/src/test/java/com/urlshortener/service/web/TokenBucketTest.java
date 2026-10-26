package com.urlshortener.service.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenBucketTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    void allowsBurstThenRefillsOverTime() {
        TokenBucket bucket = new TokenBucket(3, 60, 0); // 1 token per second

        assertThat(bucket.tryConsume(0)).isTrue();
        assertThat(bucket.tryConsume(0)).isTrue();
        assertThat(bucket.tryConsume(0)).isTrue();
        assertThat(bucket.tryConsume(0)).isFalse();
        assertThat(bucket.secondsUntilNextToken(0)).isEqualTo(1);

        assertThat(bucket.tryConsume(SECOND)).isTrue();
        assertThat(bucket.tryConsume(SECOND)).isFalse();
    }

    @Test
    void neverExceedsCapacity() {
        TokenBucket bucket = new TokenBucket(2, 60, 0);
        long muchLater = 3600 * SECOND;
        assertThat(bucket.tryConsume(muchLater)).isTrue();
        assertThat(bucket.tryConsume(muchLater)).isTrue();
        assertThat(bucket.tryConsume(muchLater)).isFalse();
    }
}
