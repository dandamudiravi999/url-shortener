package com.urlshortener.orchestrator.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Turns free-text requirements into a normalised specification: functional requirements with
 * acceptance criteria, non-functional requirements, ambiguities with clarifying questions and the
 * default assumption used if nobody answers. Deterministic (keyword catalogue), so runs are
 * reproducible and testable; an LLM-backed analyser could implement the same output contract.
 */
public final class RequirementAnalyzer {

    /** A capability the shortener domain knows how to specify. */
    public record Capability(String id, String title, List<String> triggers, List<String> acceptance,
                             List<String> codeTerms) {
    }

    public record Ambiguity(String term, String question, String defaultAssumption) {
    }

    public record Spec(String requirement, List<Capability> capabilities, List<String> nonFunctional,
                       List<Ambiguity> openAmbiguities, Map<String, String> clarified, List<String> assumptions) {

        public List<String> codeTerms() {
            return capabilities.stream().flatMap(c -> c.codeTerms().stream()).distinct().toList();
        }

        public boolean has(String capabilityId) {
            return capabilities.stream().anyMatch(c -> c.id().equals(capabilityId));
        }
    }

    static final List<Capability> CATALOGUE = List.of(
            new Capability("create", "Create short links", List.of("shorten", "short url", "short link", "url shortener"),
                    List.of("POST /api/v1/urls with a valid http(s) URL returns 201 with code and shortUrl",
                            "Non-http(s), private-network and self-referencing URLs are rejected with 400",
                            "Shortening the same permanent URL twice returns the same code (200)"),
                    List.of("ShortUrlController", "ShortUrlService", "UrlValidator", "CreateShortUrlRequest")),
            new Capability("redirect", "Redirect to the original URL", List.of("redirect", "short url", "url shortener"),
                    List.of("GET /{code} answers 302 with Location set to the original URL",
                            "Unknown codes answer 404 as problem+json"),
                    List.of("RedirectController", "resolveForRedirect", "UrlLookupService")),
            new Capability("analytics", "Click analytics", List.of("analytics", "stats", "statistics", "click count", "clicks"),
                    List.of("GET /api/v1/urls/{code}/stats returns total clicks, clicks per day and top referrers",
                            "Recording a click never delays or fails the redirect"),
                    List.of("ClickRecorder", "ClickEvent", "UrlStatsResponse", "incrementClicks")),
            new Capability("expiry", "Link expiry", List.of("expir", "ttl", "time to live"),
                    List.of("Links accept ttlSeconds or expiresAt on creation",
                            "Expired links answer 410 Gone and are not counted as clicks"),
                    List.of("expiresAt", "isExpired", "UrlExpiredException")),
            new Capability("alias", "Custom aliases", List.of("alias", "custom code", "vanity"),
                    List.of("A free alias of 3-32 [A-Za-z0-9_-] characters is used as the code",
                            "A taken alias answers 409; reserved route names answer 400"),
                    List.of("AliasValidator", "AliasConflictException")),
            new Capability("delete", "Delete links", List.of("delet", "remove link", "deactivate"),
                    List.of("DELETE /api/v1/urls/{code} answers 204 and redirects stop immediately (cache evicted)"),
                    List.of("markDeleted", "evict")),
            new Capability("click-cap", "Maximum clicks per link",
                    List.of("max clicks", "maximum clicks", "click limit", "limit the number of clicks", "maxclicks"),
                    List.of("Links accept an optional maxClicks (>= 1) on creation",
                            "Once clickCount reaches maxClicks the link answers 410 Gone",
                            "Stats expose maxClicks and remaining clicks"),
                    List.of("maxClicks", "clickCount", "click_count", "incrementClicks", "resolveForRedirect", "ShortUrl",
                            "CreateShortUrlRequest", "UrlStatsResponse", "ResolvedUrl")),
            new Capability("unique-visitors", "Unique visitors per day",
                    List.of("unique visitor", "unique users", "distinct visitors"),
                    List.of("Stats include unique visitors per day",
                            "Visitor identity is a salted hash of the client IP that rotates daily; raw IPs are never stored",
                            "Visitor hashes are deleted after the stated retention period"),
                    List.of("visitorHash", "ClickEvent", "ClickRecorder", "UrlStatsResponse", "click_event")));

    static final List<Ambiguity> VAGUE_TERMS = List.of(
            new Ambiguity("fast", "What latency target should redirects meet (for example p99 under 50 ms)?",
                    "p99 redirect latency under 50 ms at 200 req/s on one node, achieved with an in-process cache"),
            new Ambiguity("reliable", "What availability target applies, and which failures must never affect users?",
                    "99.9% availability; analytics are best-effort and must never break a redirect"),
            new Ambiguity("scalable", "What traffic volume must be supported now and in a year?",
                    "1k links/day and 100 redirects/s peak; stateless app tier so it can scale horizontally later"),
            new Ambiguity("better", "Which specific metrics or behaviours are missing today?",
                    "Add per-day clicks and top referrers; collect no personal data"),
            new Ambiguity("improve", "What measurable outcome defines the improvement?",
                    "Treat as the closest catalogue capability with existing acceptance criteria"),
            new Ambiguity("secure", "Which threats are in scope (abuse, enumeration, data leaks)?",
                    "Input validation, rate limiting on creation, non-enumerable codes, no secrets in code"),
            new Ambiguity("user-friendly", "Who are the users and what would make it friendly for them?",
                    "Documented REST API with OpenAPI/Swagger UI and RFC 7807 errors"),
            new Ambiguity("soon", "What is the delivery deadline?", "Deliverable within one iteration"),
            new Ambiguity("etc", "What else is included beyond the listed items?", "Nothing beyond the listed items"));

    private static final List<String> BASELINE_NFRS = List.of(
            "Inputs validated at the API boundary; errors returned as RFC 7807 problem+json",
            "Schema changes only through new, versioned Flyway migrations",
            "Every behaviour change covered by unit and integration tests",
            "No secrets in source, configuration or generated artifacts");

    public Spec analyze(String requirement, Map<String, String> answers) {
        String combined = (requirement + " " + String.join(" ", answers.values())).toLowerCase(Locale.ROOT);
        List<Capability> caps = CATALOGUE.stream()
                .filter(c -> c.triggers().stream().anyMatch(combined::contains))
                .toList();

        List<String> nfrs = new ArrayList<>(BASELINE_NFRS);
        if (combined.contains("rate limit") || combined.contains("abuse")) {
            nfrs.add("Link creation rate-limited per client; 429 with Retry-After when exceeded");
        }

        String lowerRequirement = requirement.toLowerCase(Locale.ROOT);
        List<Ambiguity> open = new ArrayList<>();
        Map<String, String> clarified = new LinkedHashMap<>();
        List<String> assumptions = new ArrayList<>();
        for (Ambiguity a : VAGUE_TERMS) {
            if (!Pattern.compile("\\b" + Pattern.quote(a.term()) + "\\w*").matcher(lowerRequirement).find()) {
                continue;
            }
            String answer = answers.get(a.term());
            if (answer != null && !answer.isBlank()) {
                clarified.put(a.term(), answer.strip());
                nfrs.add("Clarified '" + a.term() + "': " + answer.strip());
            } else {
                open.add(a);
                assumptions.add("'" + a.term() + "' interpreted as: " + a.defaultAssumption());
            }
        }
        if (caps.isEmpty()) {
            assumptions.add("No known capability matched; scope must be confirmed by a human before design");
        }
        return new Spec(requirement, caps, nfrs, open, clarified, assumptions);
    }

    public static String render(Spec spec) {
        StringBuilder md = new StringBuilder("# Requirements\n\n## Problem statement\n\n");
        md.append(spec.requirement().strip()).append("\n\n## Functional requirements\n\n");
        int fr = 0;
        int ac = 0;
        for (Capability c : spec.capabilities()) {
            md.append("### FR-").append(++fr).append(" ").append(c.title()).append(" (`").append(c.id()).append("`)\n\n");
            for (String a : c.acceptance()) {
                md.append("- AC-").append(++ac).append(": ").append(a).append("\n");
            }
            md.append("\n");
        }
        if (spec.capabilities().isEmpty()) {
            md.append("_None identified._\n\n");
        }
        md.append("## Non-functional requirements\n\n");
        spec.nonFunctional().forEach(n -> md.append("- ").append(n).append("\n"));
        md.append("\n## Open questions\n\n");
        if (spec.openAmbiguities().isEmpty()) {
            md.append("None.\n");
        }
        spec.openAmbiguities().forEach(a -> md.append("- [").append(a.term()).append("] ").append(a.question()).append("\n"));
        md.append("\n## Clarifications\n\n");
        if (spec.clarified().isEmpty()) {
            md.append("None yet.\n");
        }
        spec.clarified().forEach((t, a) -> md.append("- [").append(t).append("] ").append(a).append("\n"));
        md.append("\n## Assumptions\n\n");
        if (spec.assumptions().isEmpty()) {
            md.append("None.\n");
        }
        spec.assumptions().forEach(a -> md.append("- ").append(a).append("\n"));
        md.append("\n## Out of scope\n\n- User accounts and authentication\n- Multi-region deployment\n");
        return md.toString();
    }

    /** Machine-readable list consumed by the clarification agent: one "term|question" per line. */
    public static String renderOpenQuestions(Spec spec) {
        StringBuilder sb = new StringBuilder();
        spec.openAmbiguities().forEach(a -> sb.append(a.term()).append('|').append(a.question()).append('\n'));
        return sb.length() == 0 ? "none\n" : sb.toString();
    }

    public static List<String> capabilityIds(String requirementsMarkdown) {
        List<String> ids = new ArrayList<>();
        var m = Pattern.compile("### FR-\\d+ .*\\(`([a-z-]+)`\\)").matcher(requirementsMarkdown);
        while (m.find()) {
            ids.add(m.group(1));
        }
        return ids;
    }

    public static Capability capability(String id) {
        return CATALOGUE.stream().filter(c -> c.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown capability " + id));
    }
}
