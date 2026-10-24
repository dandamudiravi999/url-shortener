package com.urlshortener.orchestrator.agent;

import java.util.Map;

/** REQUIREMENTS stage: normalise the request and surface ambiguity instead of guessing silently. */
public final class RequirementsAgent implements Agent {

    private final RequirementAnalyzer analyzer = new RequirementAnalyzer();

    @Override
    public String name() {
        return "requirements-agent";
    }

    @Override
    public AgentResult execute(AgentTask task) throws NonRetryableException {
        String requirement = task.context().requirement();
        if (requirement == null || requirement.isBlank()) {
            throw new NonRetryableException("empty requirement");
        }
        RequirementAnalyzer.Spec spec = analyzer.analyze(requirement, Map.of());
        AgentResult result = AgentResult.success(spec.capabilities().size() + " functional requirements, "
                        + spec.openAmbiguities().size() + " open questions")
                .artifact("requirements", RequirementAnalyzer.render(spec))
                .artifact("open-questions", RequirementAnalyzer.renderOpenQuestions(spec))
                .decision("Scope normalised to capabilities " + spec.capabilities().stream().map(RequirementAnalyzer.Capability::id).toList(),
                        "Matched requirement wording against the domain capability catalogue; each capability brings testable acceptance criteria");
        if (!spec.openAmbiguities().isEmpty()) {
            result.decision("Proceed optimistically on " + spec.openAmbiguities().size() + " documented assumptions",
                    "Design can start in parallel with clarification; if answers change the requirements the engine re-plans downstream work");
        }
        if (spec.capabilities().isEmpty()) {
            result.requestApproval("no known capability matched the requirement; a human must confirm scope");
        }
        return result;
    }
}
