package com.urlshortener.orchestrator.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RequirementAnalyzerTest {

    private final RequirementAnalyzer analyzer = new RequirementAnalyzer();

    @Test
    void clearRequirementMapsToCapabilitiesWithoutOpenQuestions() {
        var spec = analyzer.analyze("Build a URL shortener with custom alias, expiry and click analytics; rate limit creation.", Map.of());

        assertThat(spec.capabilities()).extracting(RequirementAnalyzer.Capability::id)
                .contains("create", "redirect", "alias", "expiry", "analytics");
        assertThat(spec.openAmbiguities()).isEmpty();
        assertThat(spec.nonFunctional()).anyMatch(n -> n.contains("rate-limited"));
    }

    @Test
    void vagueTermsBecomeQuestionsWithDocumentedAssumptions() {
        var spec = analyzer.analyze("Make short links faster and more reliable with better analytics.", Map.of());

        assertThat(spec.openAmbiguities()).extracting(RequirementAnalyzer.Ambiguity::term)
                .containsExactlyInAnyOrder("fast", "reliable", "better");
        assertThat(spec.assumptions()).hasSize(3);
        assertThat(RequirementAnalyzer.render(spec)).contains("## Open questions", "[fast]");
    }

    @Test
    void answersResolveAmbiguityAndCanAddScope() {
        var spec = analyzer.analyze("Make short links faster with better analytics.",
                Map.of("fast", "p99 < 20ms", "better", "track unique visitors per day"));

        assertThat(spec.openAmbiguities()).isEmpty();
        assertThat(spec.clarified()).containsKeys("fast", "better");
        assertThat(spec.has("unique-visitors")).isTrue();
        assertThat(RequirementAnalyzer.renderOpenQuestions(spec)).isEqualTo("none\n");
    }

    @Test
    void renderedRequirementsRoundTripCapabilityIds() {
        var spec = analyzer.analyze("Add a click limit to short links", Map.of());
        assertThat(RequirementAnalyzer.capabilityIds(RequirementAnalyzer.render(spec))).contains("click-cap", "create");
    }
}
