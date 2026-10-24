package com.urlshortener.service.repository;

import com.urlshortener.service.domain.ShortUrl;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface ShortUrlRepository extends JpaRepository<ShortUrl, Long> {

    Optional<ShortUrl> findByCode(String code);

    boolean existsByCode(String code);

    /** Reusable link for de-duplication: same target, generated code, never expires, not deleted. */
    Optional<ShortUrl> findFirstByOriginalUrlAndCustomAliasFalseAndExpiresAtIsNullAndDeletedAtIsNull(String originalUrl);

    /**
     * Atomic counter update in the database, so concurrent redirects never lose clicks
     * (no read-modify-write in the JVM, no optimistic-lock retries on the hot path).
     */
    @Modifying
    @Query("update ShortUrl s set s.clickCount = s.clickCount + 1, s.lastAccessedAt = :now where s.id = :id")
    int incrementClicks(@Param("id") Long id, @Param("now") Instant now);
}
