package com.urlshortener.orchestrator.agent;

import com.urlshortener.orchestrator.core.ContextFixtures;
import com.urlshortener.orchestrator.core.StageType;
import com.urlshortener.orchestrator.core.WorkflowContext;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs against the real shortener-service sources in this repository (Maven runs tests from orchestrator/). */
class ImpactAnalysisAgentTest {

    private static final Path REPO = Path.of("..").toAbsolutePath().normalize();

    @Test
    void findsImpactedLayersEndpointsAndNextMigrationForClickCap() throws Exception {
        String requirement = "Add a click limit (maxClicks) to short links";
        WorkflowContext ctx = ContextFixtures.withArtifact(requirement, "requirements",
                RequirementAnalyzer.render(new RequirementAnalyzer().analyze(requirement, Map.of())));

        AgentResult result = new ImpactAnalysisAgent(REPO).execute(new AgentTask("r", "impact", StageType.IMPACT_ANALYSIS, 1, ctx, null));

        String impact = result.artifacts().get("impact-analysis");
        assertThat(impact)
                .contains("New capabilities: [click-cap]")
                .contains("next-migration: V2")
                .contains("ShortUrlService.java", "RedirectController.java", "ShortUrl.java")
                .contains("GET /{code:[A-Za-z0-9_-]{3,32}}");
        assertThat(impact).doesNotContain("ShortenerApplication.java");
    }

    @Test
    void missingCodebaseIsANonRetryableFailure() {
        WorkflowContext ctx = new WorkflowContext("r", "x", Clock.systemUTC());
        assertThatThrownBy(() -> new ImpactAnalysisAgent(Path.of("/nonexistent"))
                .execute(new AgentTask("r", "impact", StageType.IMPACT_ANALYSIS, 1, ctx, null)))
                .isInstanceOf(NonRetryableException.class);
    }
}
