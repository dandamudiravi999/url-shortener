package com.urlshortener.orchestrator.cli;

import com.urlshortener.orchestrator.core.ApprovalDecision;
import com.urlshortener.orchestrator.core.ApprovalGateway;
import com.urlshortener.orchestrator.core.ApprovalRequest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.Map;

/** Interactive human-in-the-loop on the terminal. End of input or an I/O error means "reject". */
public final class ConsoleApprovalGateway implements ApprovalGateway {

    private final BufferedReader in;
    private final PrintStream out;
    private final String approver;

    public ConsoleApprovalGateway(BufferedReader in, PrintStream out, String approver) {
        this.in = in;
        this.out = out;
        this.approver = approver;
    }

    @Override
    public synchronized ApprovalDecision decide(ApprovalRequest request) {
        try {
            out.println();
            out.println("=== HUMAN INPUT NEEDED: " + request.nodeId() + " (" + request.impact() + ") ===");
            out.println("Action: " + request.action());
            request.reasons().forEach(r -> out.println("  reason: " + r));
            request.evidence().forEach((k, v) -> out.println("  " + k + ": " + v));
            if (request.isClarification()) {
                Map<String, String> answers = new LinkedHashMap<>();
                for (String q : request.questions()) {
                    out.print(q + "\n  answer (blank keeps the default assumption): ");
                    String a = in.readLine();
                    if (a == null) {
                        break;
                    }
                    if (!a.isBlank()) {
                        answers.put(q, a.strip());
                    }
                }
                return answers.isEmpty() ? ApprovalDecision.reject(approver, "no answers given")
                        : ApprovalDecision.answered(approver, answers);
            }
            out.print("Approve? [y/N] ");
            String line = in.readLine();
            if (line != null && line.strip().equalsIgnoreCase("y")) {
                out.print("Comment: ");
                String c = in.readLine();
                return ApprovalDecision.approve(approver, c == null || c.isBlank() ? "approved at console" : c.strip());
            }
            return ApprovalDecision.reject(approver, line == null ? "no input (EOF)" : "declined at console");
        } catch (IOException e) {
            return ApprovalDecision.reject(approver, "console unavailable: " + e.getMessage());
        }
    }
}
