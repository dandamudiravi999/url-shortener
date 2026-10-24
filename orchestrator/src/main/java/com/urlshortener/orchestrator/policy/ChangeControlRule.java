package com.urlshortener.orchestrator.policy;

import com.urlshortener.orchestrator.core.ArtifactLookup;
import com.urlshortener.orchestrator.core.WorkflowNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CHANGE_CONTROL over the {@code changeset} artifact (lines of "ADD|MODIFY|DELETE path").
 * <ul>
 *   <li>Applied Flyway migrations are immutable: MODIFY/DELETE of db/migration/V* is denied.</li>
 *   <li>New migrations, build/CI/config files and deletions need a human approval.</li>
 *   <li>Large change sets need approval (blast radius).</li>
 * </ul>
 */
public final class ChangeControlRule implements PolicyRule {

    public static final String CHANGESET = "changeset";
    static final int MAX_FILES_WITHOUT_APPROVAL = 25;

    private static final Pattern LINE = Pattern.compile("^(ADD|MODIFY|DELETE)\\s+(\\S+)", Pattern.MULTILINE);
    private static final Pattern MIGRATION = Pattern.compile(".*db/migration/V\\d+__.*\\.sql$");
    private static final List<String> PROTECTED = List.of("pom.xml", "application.yml", ".github/workflows/", "Dockerfile");

    @Override
    public String id() {
        return "CHG-001-change-control";
    }

    @Override
    public PolicyCategory category() {
        return PolicyCategory.CHANGE_CONTROL;
    }

    @Override
    public List<PolicyFinding> evaluate(WorkflowNode node, Map<String, String> proposed, ArtifactLookup view) {
        String changeset = proposed.get(CHANGESET);
        if (changeset == null) {
            return List.of();
        }
        List<PolicyFinding> findings = new ArrayList<>();
        int files = 0;
        Matcher m = LINE.matcher(changeset);
        while (m.find()) {
            files++;
            String op = m.group(1);
            String path = m.group(2);
            boolean migration = MIGRATION.matcher(path).matches();
            if (migration && !op.equals("ADD")) {
                findings.add(deny(op + " " + path + ": applied migrations are immutable, add a new V<n>__ migration"));
            } else if (migration) {
                findings.add(approval("schema change " + path + " needs DBA/owner approval"));
            } else if (op.equals("DELETE")) {
                findings.add(approval("file deletion " + path + " needs approval"));
            } else if (PROTECTED.stream().anyMatch(path::contains)) {
                findings.add(approval(op + " of protected path " + path));
            }
        }
        if (files > MAX_FILES_WITHOUT_APPROVAL) {
            findings.add(approval("change set touches " + files + " files (> " + MAX_FILES_WITHOUT_APPROVAL + ")"));
        }
        return findings;
    }

    private PolicyFinding deny(String message) {
        return new PolicyFinding(id(), category(), Verdict.DENY, CHANGESET, message);
    }

    private PolicyFinding approval(String message) {
        return new PolicyFinding(id(), category(), Verdict.REQUIRE_APPROVAL, CHANGESET, message);
    }
}
