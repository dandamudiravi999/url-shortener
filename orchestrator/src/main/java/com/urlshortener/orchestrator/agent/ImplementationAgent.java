package com.urlshortener.orchestrator.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IMPLEMENTATION stage: proposes a change set (ADD/MODIFY/DELETE per file) and a per-file plan.
 * The change set is what change-control policy reviews. Two strategies exist so the engine's
 * policy + fallback path can be shown with a realistic mistake: editing an applied migration.
 */
public final class ImplementationAgent implements Agent {

    public enum Strategy {
        /** Fast but unsafe for brownfield: alters the existing V1 migration in place. */
        IN_PLACE_SCHEMA_EDIT,
        /** Safe: schema changes go into a new migration. */
        ADDITIVE_MIGRATION
    }

    private static final String SVC = "shortener-service/src/main/java/com/urlshortener/service/";
    private static final String TEST = "shortener-service/src/test/java/com/urlshortener/service/";
    private static final String MIGRATIONS = "shortener-service/src/main/resources/db/migration/";
    private static final Pattern IMPACTED_FILE = Pattern.compile("^- `([^`]+)`", Pattern.MULTILINE);
    private static final Pattern NEXT_MIGRATION = Pattern.compile("next-migration: V(\\d+)");

    private final Strategy strategy;

    public ImplementationAgent(Strategy strategy) {
        this.strategy = strategy;
    }

    @Override
    public String name() {
        return "implementation-agent(" + strategy.name().toLowerCase().replace('_', '-') + ")";
    }

    @Override
    public AgentResult execute(AgentTask task) throws NonRetryableException {
        String impact = task.context().contentOf("impact-analysis");
        List<String> caps = RequirementAnalyzer.capabilityIds(task.context().contentOf("requirements"));
        return impact.isBlank() ? greenfield(caps) : brownfield(impact, caps);
    }

    private AgentResult greenfield(List<String> caps) {
        List<String> files = new ArrayList<>(List.of(
                "ADD pom.xml",
                "ADD shortener-service/pom.xml",
                "ADD shortener-service/src/main/resources/application.yml",
                "ADD " + MIGRATIONS + "V1__create_short_url_tables.sql",
                "ADD " + SVC + "ShortenerApplication.java",
                "ADD " + SVC + "config/ShortenerProperties.java",
                "ADD " + SVC + "domain/ShortUrl.java",
                "ADD " + SVC + "repository/ShortUrlRepository.java",
                "ADD " + SVC + "service/ShortUrlService.java",
                "ADD " + SVC + "service/UrlValidator.java",
                "ADD " + SVC + "service/Base62CodeGenerator.java",
                "ADD " + SVC + "service/UrlLookupService.java",
                "ADD " + SVC + "web/ShortUrlController.java",
                "ADD " + SVC + "web/RedirectController.java",
                "ADD " + SVC + "web/GlobalExceptionHandler.java",
                "ADD " + TEST + "service/ShortUrlServiceTest.java",
                "ADD " + TEST + "web/ShortUrlApiIntegrationTest.java"));
        if (caps.contains("analytics")) {
            files.add("ADD " + SVC + "domain/ClickEvent.java");
            files.add("ADD " + SVC + "service/ClickRecorder.java");
        }
        if (caps.contains("alias")) {
            files.add("ADD " + SVC + "service/AliasValidator.java");
        }
        String changeset = String.join("\n", files) + "\n";
        String plan = "# Implementation plan (greenfield)\n\n"
                + "Layered Spring Boot 3 / Java 21 service. Each file below is created; the reference implementation "
                + "lives in `shortener-service/` of this repository.\n\n```\n" + changeset + "```\n\n"
                + "Order: schema -> domain/repository -> service rules -> web layer -> tests.\n";
        return AgentResult.success(files.size() + " files in change set")
                .artifact("changeset", changeset)
                .artifact("implementation-plan", plan)
                .decision("Greenfield layout: config / domain / repository / service / web",
                        "Conventional Spring layering keeps each concern unit-testable and easy to review");
    }

    private AgentResult brownfield(String impact, List<String> caps) throws NonRetryableException {
        List<String> impacted = new ArrayList<>();
        Matcher m = IMPACTED_FILE.matcher(impact);
        while (m.find()) {
            impacted.add(m.group(1));
        }
        if (impacted.isEmpty()) {
            throw new NonRetryableException("impact analysis lists no files; nothing to implement");
        }
        Matcher nm = NEXT_MIGRATION.matcher(impact);
        int next = nm.find() ? Integer.parseInt(nm.group(1)) : 2;
        String feature = caps.contains("click-cap") ? "add_max_clicks"
                : caps.contains("unique-visitors") ? "add_visitor_hash" : "brownfield_change";

        List<String> lines = new ArrayList<>();
        for (String file : impacted) {
            if (file.contains("db/migration/")) {
                continue;
            }
            lines.add("MODIFY " + file);
        }
        if (strategy == Strategy.IN_PLACE_SCHEMA_EDIT) {
            lines.add("MODIFY " + MIGRATIONS + "V" + (next - 1) + "__create_short_url_tables.sql");
        } else {
            lines.add("ADD " + MIGRATIONS + "V" + next + "__" + feature + ".sql");
        }
        lines.add("ADD " + TEST + "service/" + (caps.contains("click-cap") ? "ClickCapTest.java" : "ChangeTest.java"));
        String changeset = String.join("\n", lines) + "\n";

        StringBuilder plan = new StringBuilder("# Implementation plan (brownfield)\n\n");
        plan.append("Strategy: ").append(strategy).append("\n\n```\n").append(changeset).append("```\n\n");
        if (caps.contains("click-cap")) {
            plan.append("- Migration: `ALTER TABLE short_url ADD COLUMN max_clicks BIGINT NULL;`\n")
                    .append("- `CreateShortUrlRequest`: optional `@Positive Long maxClicks`\n")
                    .append("- `ShortUrl` / `ResolvedUrl`: carry `maxClicks` so the cached lookup can enforce it\n")
                    .append("- `ShortUrlService.resolveForRedirect`: 410 when clickCount >= maxClicks (DB counter is authoritative)\n")
                    .append("- `UrlStatsResponse`: add `maxClicks` and `remainingClicks`\n")
                    .append("- Tests: cap reached -> 410, null cap -> unlimited, validation of maxClicks <= 0\n");
        }
        return AgentResult.success(lines.size() + " file changes proposed (" + strategy + ")")
                .artifact("changeset", changeset)
                .artifact("implementation-plan", plan.toString())
                .decision("Change set derived from impact analysis using " + strategy,
                        strategy == Strategy.ADDITIVE_MIGRATION
                                ? "Additive migration is safe for environments where V" + (next - 1) + " is already applied"
                                : "Quickest edit; relies on policy to catch unsafe schema changes");
    }
}
