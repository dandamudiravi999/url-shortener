package com.urlshortener.orchestrator.agent;

import com.urlshortener.orchestrator.core.Gate;
import com.urlshortener.orchestrator.core.Impact;
import com.urlshortener.orchestrator.core.StageType;
import com.urlshortener.orchestrator.core.WorkflowNode;

import java.util.List;

/**
 * DESIGN stage: derives API contract, data model and key decisions from the requirements.
 * Also re-plans: when the design exposes a public write path or touches personal data it inserts a
 * security-review node that the release must wait for (agents can add checks, never remove them).
 */
public final class DesignAgent implements Agent {

    public static final String SECURITY_REVIEW_NODE = "security-review";

    private final String implementationNode;
    private final String releaseNode;

    public DesignAgent(String implementationNode, String releaseNode) {
        this.implementationNode = implementationNode;
        this.releaseNode = releaseNode;
    }

    @Override
    public String name() {
        return "design-agent";
    }

    @Override
    public AgentResult execute(AgentTask task) throws NonRetryableException {
        String requirements = task.context().contentOf("requirements");
        List<String> caps = RequirementAnalyzer.capabilityIds(requirements);
        if (caps.isEmpty()) {
            throw new NonRetryableException("requirements contain no functional requirements to design for");
        }
        boolean brownfield = task.context().has("impact-analysis");
        StringBuilder md = new StringBuilder("# Design\n\n");
        md.append("Mode: ").append(brownfield ? "brownfield change to the existing shortener-service" : "greenfield service").append("\n\n");
        md.append("## Components\n\n| Component | Responsibility |\n|---|---|\n")
                .append("| ShortUrlController | REST API under /api/v1/urls (create, details, stats, delete) |\n")
                .append("| RedirectController | GET /{code} -> 302; hot path, cached lookup |\n")
                .append("| ShortUrlService | Business rules: validation, code allocation with bounded retry, expiry |\n")
                .append("| UrlLookupService | Caffeine-cached code -> target lookup |\n")
                .append("| ClickRecorder | Async, best-effort click analytics (never fails a redirect) |\n")
                .append("| RateLimitFilter | Token bucket per client on link creation |\n")
                .append("| Flyway + JPA/H2 | Versioned schema; H2 in dev, Postgres-compatible SQL |\n\n");

        md.append("## API contract\n\n| Method | Path | Success | Errors |\n|---|---|---|---|\n");
        if (caps.contains("create")) md.append("| POST | /api/v1/urls | 201 / 200 (reused) | 400, 409, 429 |\n");
        if (caps.contains("redirect")) md.append("| GET | /{code} | 302 | 404, 410 |\n");
        if (caps.contains("analytics")) md.append("| GET | /api/v1/urls/{code}/stats | 200 | 404 |\n");
        if (caps.contains("delete")) md.append("| DELETE | /api/v1/urls/{code} | 204 | 404 |\n");
        if (caps.contains("click-cap")) md.append("| POST | /api/v1/urls (new optional field `maxClicks`) | 201 | 400 |\n")
                .append("| GET | /{code} once clickCount >= maxClicks | - | 410 |\n");
        if (caps.contains("unique-visitors")) md.append("| GET | /api/v1/urls/{code}/stats (new field `uniqueVisitorsByDay`) | 200 | 404 |\n");

        md.append("\n## Data model\n\n- `short_url(id, code UNIQUE, original_url, custom_alias, created_at, expires_at, deleted_at, click_count, last_accessed_at, version)`\n")
                .append("- `click_event(id, short_url_id FK, occurred_at, referrer, user_agent)`; no personal data stored\n");
        if (caps.contains("click-cap")) {
            md.append("- New nullable column `short_url.max_clicks` via a new migration (existing rows unaffected)\n");
        }
        if (caps.contains("unique-visitors")) {
            md.append("- New column `click_event.visitor_hash`: salted SHA-256 of client IP, salt rotated daily; raw IP never persisted; rows purged after the retention period\n");
        }

        md.append("\n## Key decisions\n\n")
                .append("- 302 + Cache-Control: no-store, so every click is counted and delete/expiry take effect immediately\n")
                .append("- Random Base62 codes (7 chars, 62^7 keyspace): non-enumerable; collisions retried a bounded number of times\n")
                .append("- Atomic SQL increment for click counts: no lost updates under concurrency\n")
                .append("- Analytics are best-effort and asynchronous: availability of redirects beats completeness of stats\n");
        if (caps.contains("click-cap")) {
            md.append("- Click cap enforced on the redirect path using the cached lookup plus the authoritative DB counter; at most a few clicks over the cap under heavy concurrency, documented as accepted\n");
        }

        AgentResult result = AgentResult.success("design for " + caps)
                .artifact("design", md.toString())
                .decision("Layered Spring Boot service with cached redirect path and async analytics",
                        "Redirect latency and availability dominate; analytics can lag without user impact");

        boolean publicWrite = caps.contains("create") || caps.contains("click-cap");
        boolean personalData = caps.contains("unique-visitors");
        if (publicWrite || personalData) {
            WorkflowNode review = WorkflowNode.builder(SECURITY_REVIEW_NODE, StageType.SECURITY_REVIEW, new SecurityReviewAgent())
                    .dependsOn(implementationNode)
                    .entryGate(Gate.requiresArtifacts("design", "changeset"))
                    .exitGate(Gate.contentContains("security-review", "verdict: PASS"))
                    .impact(Impact.MEDIUM)
                    .description("threat review of design and change set")
                    .build();
            result.planChange(new PlanChange(review, List.of(releaseNode),
                    personalData ? "design processes personal data" : "design exposes a public write endpoint"));
            result.decision("Inserted security review before release",
                    personalData ? "Personal data handling must be reviewed" : "Public write endpoints are an abuse surface");
        }
        return result;
    }
}
