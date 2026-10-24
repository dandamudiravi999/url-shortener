package com.urlshortener.service.repository;

import com.urlshortener.service.domain.ClickEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ClickEventRepository extends JpaRepository<ClickEvent, Long> {

    @Query("select e.occurredAt from ClickEvent e where e.shortUrlId = :id and e.occurredAt >= :since")
    List<Instant> findClickTimesSince(@Param("id") Long shortUrlId, @Param("since") Instant since);

    @Query("""
            select e.referrer as referrer, count(e) as clicks
            from ClickEvent e
            where e.shortUrlId = :id and e.referrer is not null
            group by e.referrer
            order by count(e) desc
            """)
    List<ReferrerCount> topReferrers(@Param("id") Long shortUrlId, org.springframework.data.domain.Pageable page);

    interface ReferrerCount {
        String getReferrer();

        long getClicks();
    }
}
