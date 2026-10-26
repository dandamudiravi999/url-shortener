package com.urlshortener.orchestrator.scenario;

import com.urlshortener.orchestrator.agent.TestAgent;
import com.urlshortener.orchestrator.core.EngineConfig;
import com.urlshortener.orchestrator.core.NodeState;
import com.urlshortener.orchestrator.core.RunReport;
import com.urlshortener.orchestrator.core.RunStatus;
import com.urlshortener.orchestrator.core.ScriptedApprovalGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end runs of the three assessment scenarios against the real shortener-service sources.
 * Test evidence comes from a fixture Surefire report so this test does not depend on build order.
 */
class ScenarioIntegrationTest {

    private static final Path REPO = Path.of("..").toAbsolutePath().normalize();

    @TempDir
    Path tmp;

    private Path reports;

    @BeforeEach
    void surefireFixture() throws Exception {
        reports = Files.createDirectories(tmp.resolve("reports"));
        Files.writeString(reports.resolve("TEST-com.example.FixtureTest.xml"),
                "<?xml version=\"1.0\"?><testsuite name=\"com.example.FixtureTest\" time=\"0.1\" tests=\"12\" errors=\"0\" skipped=\"0\" failures=\"0\"></testsuite>");
    }

    private RunReport run(Scenario scenario, Set<String> fail, ScriptedApprovalGateway.Builder human) throws Exception {
        ScenarioEnvironment env = new ScenarioEnvironment(REPO, reports, TestAgent.Mode.REPORTS, (c, d, t) -> null, fail);
        EngineConfig config = EngineConfig.defaults().withKillSwitch(tmp.resolve("STOP"));
        return new ScenarioRunner(env, config, Clock.systemUTC()).run(scenario, human.build(), tmp.resolve("out-" + scenario.name()));
    }

    @Test
    void greenfieldRecoversFromTransientFailureAndAddsSecurityReview() throws Exception {
        Scenario s = new GreenfieldScenario();
        RunReport r = run(s, Set.of(), s.scriptedHuman());

        assertThat(r.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(r.node("testing").attempts()).isEqualTo(2);
        assertThat(r.node("security-review").state()).isEqualTo(NodeState.SUCCEEDED);
        assertThat(r.node("release-readiness").dependsOn()).contains("security-review");
        assertThat(r.metrics().retries()).isEqualTo(1);
        assertThat(r.metrics().mttrMillis()).isNotNull();
        assertThat(r.artifact("test-report").content()).contains("status: PASSED", "tests: 12");
        assertThat(r.auditChainIntact()).isTrue();
        assertThat(tmp.resolve("out-greenfield/SUMMARY.md")).exists();
        assertThat(tmp.resolve("out-greenfield/audit.jsonl")).exists();
    }

    @Test
    void brownfieldDeniesInPlaceMigrationEditAndFallsBackToAdditiveMigration() throws Exception {
        Scenario s = new BrownfieldScenario();
        RunReport r = run(s, Set.of(), s.scriptedHuman());

        assertThat(r.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(r.metrics().policyDenials()).isEqualTo(1);
        assertThat(r.metrics().fallbacks()).isEqualTo(1);
        assertThat(r.node("implementation").agent()).isEqualTo("implementation-agent(additive-migration)");
        assertThat(r.artifact("changeset").content())
                .contains("ADD shortener-service/src/main/resources/db/migration/V2__add_max_clicks.sql")
                .doesNotContain("MODIFY shortener-service/src/main/resources/db/migration/V1");
        assertThat(r.artifact("impact-analysis").content()).contains("New capabilities: [click-cap]");
        assertThat(r.approvals()).anyMatch(a -> a.nodeId().equals("implementation") && a.approved());
    }

    @Test
    void ambiguousClarifiesThenReplansOnTheNewRequirements() throws Exception {
        Scenario s = new AmbiguousScenario();
        RunReport r = run(s, Set.of(), s.scriptedHuman());

        assertThat(r.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(r.artifact("requirements").version()).isEqualTo(2);
        assertThat(r.artifact("requirements").content()).contains("Unique visitors per day");
        assertThat(r.artifact("open-questions").content()).isEqualTo("none\n");
        assertThat(r.metrics().replans()).isGreaterThanOrEqualTo(1);
        assertThat(r.node("impact-analysis").executions()).isEqualTo(2);
        assertThat(r.artifact("design").content()).contains("visitor_hash");
        assertThat(r.node("security-review").state()).isEqualTo(NodeState.SUCCEEDED);
    }

    @Test
    void ambiguousWithoutAnswersNeedsSignOffOnAssumptions() throws Exception {
        Scenario s = new AmbiguousScenario();
        // Same human, but unable to answer clarification questions and unwilling to sign off on assumptions.
        RunReport r = run(s, Set.of(), ScriptedApprovalGateway.builder("absent-owner")
                .approve("impact-analysis", "ok").approve("implementation", "ok").approve("release-readiness", "ok"));

        assertThat(r.status()).isEqualTo(RunStatus.FAILED);
        assertThat(r.node("clarification").state()).isEqualTo(NodeState.REJECTED);
        assertThat(r.node("release-readiness").state()).isEqualTo(NodeState.SKIPPED);
    }

    @Test
    void forcedFailureRollsBackCompletedSideEffects() throws Exception {
        Scenario s = new GreenfieldScenario();
        RunReport r = run(s, Set.of("testing"), s.scriptedHuman());

        assertThat(r.status()).isEqualTo(RunStatus.FAILED);
        assertThat(r.node("implementation").state()).isEqualTo(NodeState.ROLLED_BACK);
        assertThat(r.node("release-readiness").state()).isEqualTo(NodeState.SKIPPED);
        assertThat(r.metrics().rollbacks()).isEqualTo(1);
    }
}
