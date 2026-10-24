package com.urlshortener.orchestrator.agent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * IMPACT_ANALYSIS stage (brownfield): reads the real shortener-service source tree to find which
 * modules, APIs, data and tests a change touches. Capabilities whose code terms all already exist are
 * treated as existing behaviour; only new capabilities drive the impact set.
 */
public final class ImpactAnalysisAgent implements Agent {

    private static final Pattern ENDPOINT = Pattern.compile("@(Get|Post|Put|Delete|Patch)Mapping\\(\"([^\"]*)\"");
    private static final Pattern CLASS_MAPPING = Pattern.compile("@RequestMapping\\(\"([^\"]*)\"");
    private static final Pattern MIGRATION_VERSION = Pattern.compile("V(\\d+)__.*\\.sql");

    private final Path repoRoot;

    public ImpactAnalysisAgent(Path repoRoot) {
        this.repoRoot = repoRoot;
    }

    @Override
    public String name() {
        return "impact-analysis-agent";
    }

    @Override
    public AgentResult execute(AgentTask task) throws Exception {
        Path src = repoRoot.resolve("shortener-service/src");
        if (!Files.isDirectory(src)) {
            throw new NonRetryableException("codebase not found at " + src.toAbsolutePath());
        }
        Map<Path, String> files = readSources(src);
        List<String> capIds = RequirementAnalyzer.capabilityIds(task.context().contentOf("requirements"));

        List<String> newCaps = new ArrayList<>();
        List<String> searchTerms = new ArrayList<>();
        for (String id : capIds) {
            RequirementAnalyzer.Capability cap = RequirementAnalyzer.capability(id);
            List<String> missing = cap.codeTerms().stream().filter(t -> files.values().stream().noneMatch(c -> mentions(c, t))).toList();
            if (!missing.isEmpty()) {
                newCaps.add(id);
                cap.codeTerms().stream().filter(t -> !missing.contains(t)).forEach(t -> {
                    if (!searchTerms.contains(t)) searchTerms.add(t);
                });
            }
        }
        if (newCaps.isEmpty()) {
            return AgentResult.success("all requested capabilities already exist; no code impact")
                    .artifact("impact-analysis", "# Impact analysis\n\nAll requested capabilities already exist in the codebase.\nimpacted-files: 0\n")
                    .requestApproval("requirement appears to be already implemented; confirm before continuing");
        }

        Map<String, List<String>> byLayer = new TreeMap<>();
        Map<String, List<String>> matchedTerms = new LinkedHashMap<>();
        List<String> endpoints = new ArrayList<>();
        int latestMigration = 0;
        for (var e : files.entrySet()) {
            String rel = repoRoot.relativize(e.getKey()).toString().replace('\\', '/');
            Matcher mv = MIGRATION_VERSION.matcher(e.getKey().getFileName().toString());
            if (mv.matches()) {
                latestMigration = Math.max(latestMigration, Integer.parseInt(mv.group(1)));
            }
            List<String> hits = searchTerms.stream().filter(t -> mentions(e.getValue(), t)).toList();
            if (hits.isEmpty()) {
                continue;
            }
            matchedTerms.put(rel, hits);
            byLayer.computeIfAbsent(layerOf(rel), k -> new ArrayList<>()).add(rel);
            if (rel.contains("/web/") && rel.endsWith("Controller.java")) {
                Matcher cm = CLASS_MAPPING.matcher(e.getValue());
                String base = cm.find() ? cm.group(1) : "";
                Matcher em = ENDPOINT.matcher(e.getValue());
                while (em.find()) {
                    endpoints.add(em.group(1).toUpperCase() + " " + base + em.group(2));
                }
            }
        }

        StringBuilder md = new StringBuilder("# Impact analysis\n\n");
        md.append("New capabilities: ").append(newCaps).append("\n");
        md.append("Search terms (existing symbols the change builds on): ").append(searchTerms).append("\n");
        md.append("impacted-files: ").append(matchedTerms.size()).append("\n");
        md.append("latest-migration: V").append(latestMigration).append("\n");
        md.append("next-migration: V").append(latestMigration + 1).append("\n\n");
        md.append("## Impacted files by layer\n\n");
        byLayer.forEach((layer, list) -> {
            md.append("### ").append(layer).append("\n\n");
            list.forEach(f -> md.append("- `").append(f).append("` (").append(String.join(", ", matchedTerms.get(f))).append(")\n"));
            md.append("\n");
        });
        md.append("## Impacted API endpoints\n\n");
        endpoints.stream().distinct().forEach(ep -> md.append("- ").append(ep).append("\n"));
        md.append("\n## Data flow\n\n")
                .append("Create: ShortUrlController -> ShortUrlService -> ShortUrlRepository -> short_url\n\n")
                .append("Redirect (hot path): RedirectController -> ShortUrlService.resolveForRedirect -> UrlLookupService (cache) -> ClickRecorder (async) -> short_url.click_count + click_event\n\n")
                .append("## Risks\n\n")
                .append("- Redirect hot path changes can raise latency or break every link: keep the check O(1) on cached data\n")
                .append("- Schema change needs a new migration V").append(latestMigration + 1).append("; editing V").append(latestMigration)
                .append(" would break every environment where it is already applied\n")
                .append("- Cached lookups (ResolvedUrl) must carry any new field, or the cache serves stale rules for up to its TTL\n");

        return AgentResult.success(matchedTerms.size() + " files impacted across " + byLayer.keySet())
                .artifact("impact-analysis", md.toString())
                .decision("Change confined to layers " + byLayer.keySet(),
                        "Static scan of shortener-service for symbols the new capabilities build on");
    }

    private static Map<Path, String> readSources(Path src) throws IOException {
        Map<Path, String> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(src)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".sql") || p.toString().endsWith(".yml"))
                    .forEach(p -> {
                        try {
                            files.put(p, Files.readString(p));
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        }
        return files;
    }

    /** Whole-identifier match, so "ShortUrl" does not match "shortUrlId" or "ShortUrlApiIntegrationTest". */
    static boolean mentions(String source, String term) {
        return Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(term) + "(?![A-Za-z0-9_])").matcher(source).find();
    }

    static String layerOf(String path) {
        if (path.contains("/test/")) return "tests";
        if (path.contains("db/migration")) return "schema";
        if (path.contains("/web/")) return "web";
        if (path.contains("/service/service/")) return "service";
        if (path.contains("/domain/")) return "domain";
        if (path.contains("/repository/")) return "repository";
        if (path.contains("/config/") || path.endsWith(".yml")) return "config";
        return "other";
    }
}
