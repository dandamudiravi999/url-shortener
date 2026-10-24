package com.urlshortener.orchestrator.agent;

import com.urlshortener.orchestrator.core.ApprovalDecision;
import com.urlshortener.orchestrator.core.ApprovalRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CLARIFICATION stage: asks the human the open questions. Answers produce a new requirements version,
 * which the engine detects as an upstream change and uses to re-plan any work done on assumptions.
 */
public final class ClarificationAgent implements Agent {

    private final RequirementAnalyzer analyzer = new RequirementAnalyzer();

    @Override
    public String name() {
        return "clarification-agent";
    }

    @Override
    public AgentResult execute(AgentTask task) {
        String open = task.context().contentOf("open-questions").strip();
        if (open.isEmpty() || open.equals("none")) {
            return AgentResult.success("no open questions");
        }
        Map<String, String> questionToTerm = new LinkedHashMap<>();
        for (String line : open.split("\n")) {
            String[] parts = line.split("\\|", 2);
            questionToTerm.put("[" + parts[0] + "] " + parts[1], parts[0]);
        }
        ApprovalDecision decision = task.human().decide(
                ApprovalRequest.clarification(task.runId(), task.nodeId(), new ArrayList<>(questionToTerm.keySet())));

        Map<String, String> answersByTerm = new LinkedHashMap<>();
        decision.answers().forEach((question, answer) -> {
            String term = questionToTerm.get(question);
            if (term != null && answer != null && !answer.isBlank()) {
                answersByTerm.put(term, answer);
            }
        });
        if (answersByTerm.isEmpty()) {
            return AgentResult.success("no answers; continuing on documented assumptions")
                    .decision("Continue on default assumptions", "Human gave no answers: " + decision.comment())
                    .requestApproval("work continues on " + questionToTerm.size() + " unconfirmed assumptions");
        }

        RequirementAnalyzer.Spec spec = analyzer.analyze(task.context().requirement(), answersByTerm);
        List<String> clarified = new ArrayList<>(answersByTerm.keySet());
        StringBuilder log = new StringBuilder("# Clarifications\n\n");
        answersByTerm.forEach((t, a) -> log.append("- **").append(t).append("**: ").append(a).append("\n"));
        log.append("\nAnswered by: ").append(decision.approver()).append("\n");
        return AgentResult.success(clarified.size() + " questions answered; requirements updated")
                .artifact("requirements", RequirementAnalyzer.render(spec))
                .artifact("open-questions", RequirementAnalyzer.renderOpenQuestions(spec))
                .artifact("clarifications", log.toString())
                .decision("Requirements revised with clarified terms " + clarified,
                        "Human answers replace default assumptions; downstream work based on the old version is now stale");
    }
}
