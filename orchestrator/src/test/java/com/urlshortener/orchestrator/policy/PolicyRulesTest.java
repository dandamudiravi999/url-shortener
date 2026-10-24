package com.urlshortener.orchestrator.policy;

import com.urlshortener.orchestrator.TestAgents;
import com.urlshortener.orchestrator.core.ArtifactLookup;
import com.urlshortener.orchestrator.core.StageType;
import com.urlshortener.orchestrator.core.WorkflowNode;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyRulesTest {

    private static final ArtifactLookup EMPTY = name -> Optional.empty();
    private static final WorkflowNode IMPL = WorkflowNode.builder("impl", StageType.IMPLEMENTATION, TestAgents.producing("x", "x", "x")).build();
    private static final WorkflowNode RELEASE = WorkflowNode.builder("rel", StageType.RELEASE_READINESS, TestAgents.producing("x", "x", "x")).build();

    @Test
    void secretsAreDenied() {
        var findings = new SecretLeakRule().evaluate(IMPL, Map.of(
                "a", "key AKIAABCDEFGHIJKLMNOP", "b", "-----BEGIN RSA PRIVATE KEY-----", "c", "api_key: 'abcdef123456'",
                "clean", "password must come from the environment"), EMPTY);
        assertThat(findings).hasSize(3).allMatch(f -> f.verdict() == Verdict.DENY);
    }

    @Test
    void appliedMigrationsAreImmutableNewOnesNeedApproval() {
        var rule = new ChangeControlRule();
        var edit = rule.evaluate(IMPL, Map.of("changeset", "MODIFY src/main/resources/db/migration/V1__init.sql\n"), EMPTY);
        var add = rule.evaluate(IMPL, Map.of("changeset", "ADD src/main/resources/db/migration/V2__more.sql\n"), EMPTY);
        var code = rule.evaluate(IMPL, Map.of("changeset", "MODIFY src/main/java/Foo.java\n"), EMPTY);
        var build = rule.evaluate(IMPL, Map.of("changeset", "MODIFY pom.xml\nDELETE src/main/java/Old.java\n"), EMPTY);

        assertThat(new PolicyEvaluation(edit).verdict()).isEqualTo(Verdict.DENY);
        assertThat(new PolicyEvaluation(add).verdict()).isEqualTo(Verdict.REQUIRE_APPROVAL);
        assertThat(code).isEmpty();
        assertThat(build).hasSize(2).allMatch(f -> f.verdict() == Verdict.REQUIRE_APPROVAL);
    }

    @Test
    void largeChangeSetsNeedApproval() {
        String many = "MODIFY src/F.java\n".repeat(ChangeControlRule.MAX_FILES_WITHOUT_APPROVAL + 1);
        assertThat(new ChangeControlRule().evaluate(IMPL, Map.of("changeset", many), EMPTY))
                .anyMatch(f -> f.message().contains("touches"));
    }

    @Test
    void personalDataWithoutRetentionNeedsApproval() {
        var rule = new PersonalDataRule();
        assertThat(rule.evaluate(IMPL, Map.of("design", "We store the client IP address for each click."), EMPTY))
                .singleElement().satisfies(f -> assertThat(f.verdict()).isEqualTo(Verdict.REQUIRE_APPROVAL));
        assertThat(rule.evaluate(IMPL, Map.of("design", "We store a hashed client IP with 30-day retention."), EMPTY)).isEmpty();
    }

    @Test
    void releaseNeedsPassingEvidenceAndAlwaysASignOff() {
        var rule = new ReleaseComplianceRule();
        var missing = new PolicyEvaluation(rule.evaluate(RELEASE, Map.of(), EMPTY));
        assertThat(missing.verdict()).isEqualTo(Verdict.DENY);

        Map<String, String> evidence = Map.of("test-report", "status: PASSED", "documentation", "d", "requirements", "r");
        ArtifactLookup view = name -> Optional.ofNullable(evidence.get(name))
                .map(c -> com.urlshortener.orchestrator.core.Artifact.of(name, c, "t", 1, java.time.Instant.now()));
        var ok = new PolicyEvaluation(rule.evaluate(RELEASE, Map.of(), view));
        assertThat(ok.verdict()).isEqualTo(Verdict.REQUIRE_APPROVAL);
        assertThat(rule.evaluate(IMPL, Map.of(), EMPTY)).isEmpty();
    }

    @Test
    void ruleThatThrowsFailsClosed() {
        PolicyRule broken = new PolicyRule() {
            public String id() { return "broken"; }
            public PolicyCategory category() { return PolicyCategory.SECURITY; }
            public java.util.List<PolicyFinding> evaluate(WorkflowNode n, Map<String, String> p, ArtifactLookup v) {
                throw new IllegalStateException("boom");
            }
        };
        assertThat(new PolicyEngine(java.util.List.of(broken)).evaluate(IMPL, Map.of(), EMPTY).verdict()).isEqualTo(Verdict.DENY);
    }
}
