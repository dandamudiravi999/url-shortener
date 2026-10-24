package com.urlshortener.orchestrator.policy;

import com.urlshortener.orchestrator.core.ArtifactLookup;
import com.urlshortener.orchestrator.core.WorkflowNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * COMPLIANCE: analytics are where a URL shortener collects personal data. A design or change that
 * stores IP addresses, emails or geolocation must state a retention period or anonymisation,
 * otherwise a human (privacy owner) has to sign it off.
 */
public final class PersonalDataRule implements PolicyRule {

    private static final List<String> PERSONAL_DATA = List.of("ip address", "client ip", "email", "geolocation", "precise location");
    private static final List<String> MITIGATIONS = List.of("retention", "anonymi", "hashed", "truncated ip", "no personal data");

    @Override
    public String id() {
        return "CMP-001-personal-data";
    }

    @Override
    public PolicyCategory category() {
        return PolicyCategory.COMPLIANCE;
    }

    @Override
    public List<PolicyFinding> evaluate(WorkflowNode node, Map<String, String> proposed, ArtifactLookup view) {
        List<PolicyFinding> findings = new ArrayList<>();
        proposed.forEach((name, content) -> {
            String text = content.toLowerCase(Locale.ROOT);
            List<String> found = PERSONAL_DATA.stream().filter(p -> mentionsStoring(text, p)).toList();
            boolean mitigated = MITIGATIONS.stream().anyMatch(text::contains);
            if (!found.isEmpty() && !mitigated) {
                findings.add(new PolicyFinding(id(), category(), Verdict.REQUIRE_APPROVAL, name,
                        "stores personal data " + found + " without a stated retention/anonymisation policy"));
            }
        });
        return findings;
    }

    private static boolean mentionsStoring(String text, String term) {
        int i = text.indexOf(term);
        if (i < 0) {
            return false;
        }
        String window = text.substring(Math.max(0, i - 80), Math.min(text.length(), i + 80));
        return window.contains("store") || window.contains("persist") || window.contains("record")
                || window.contains("collect") || window.contains("column");
    }
}
