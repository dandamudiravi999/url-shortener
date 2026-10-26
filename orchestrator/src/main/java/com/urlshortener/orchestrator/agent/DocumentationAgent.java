package com.urlshortener.orchestrator.agent;

/** DOCUMENTATION stage: assembles reviewer-facing docs from the committed artifacts. */
public final class DocumentationAgent implements Agent {

    @Override
    public String name() {
        return "documentation-agent";
    }

    @Override
    public AgentResult execute(AgentTask task) {
        var ctx = task.context();
        String design = ctx.contentOf("design");
        String api = section(design, "## API contract");
        String md = "# Change documentation\n\n"
                + "## What changes\n\n" + section(ctx.contentOf("requirements"), "## Functional requirements") + "\n\n"
                + "## API\n\n" + (api.isBlank() ? "No API change." : api) + "\n\n"
                + "## Design decisions\n\n" + section(design, "## Key decisions") + "\n\n"
                + (ctx.has("impact-analysis") ? "## Impact\n\n" + section(ctx.contentOf("impact-analysis"), "## Impacted files by layer") + "\n\n" : "")
                + "## How to verify\n\n```bash\nmvn verify\nmvn -pl shortener-service spring-boot:run\n"
                + "curl -s -X POST localhost:8080/api/v1/urls -H 'Content-Type: application/json' -d '{\"url\":\"https://example.com\"}'\n```\n\n"
                + "## Operational notes\n\n- Health: GET /actuator/health; metrics: /actuator/metrics/shortener.redirects\n"
                + "- Schema migrations run automatically on start (Flyway)\n";
        return AgentResult.success("documentation assembled")
                .artifact("documentation", md)
                .decision("Docs generated from requirements, design and impact artifacts",
                        "Generated from the same artifacts reviewers approved, so docs cannot drift from the plan");
    }

    static String section(String markdown, String heading) {
        int start = markdown.indexOf(heading);
        if (start < 0) {
            return "";
        }
        int body = markdown.indexOf('\n', start) + 1;
        int next = markdown.indexOf("\n## ", body);
        return markdown.substring(body, next < 0 ? markdown.length() : next).strip();
    }
}
