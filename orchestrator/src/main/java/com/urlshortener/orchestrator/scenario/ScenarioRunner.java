package com.urlshortener.orchestrator.scenario;

import com.urlshortener.orchestrator.audit.AuditLog;
import com.urlshortener.orchestrator.core.ApprovalGateway;
import com.urlshortener.orchestrator.core.EngineConfig;
import com.urlshortener.orchestrator.core.ReportWriter;
import com.urlshortener.orchestrator.core.RunReport;
import com.urlshortener.orchestrator.core.WorkflowContext;
import com.urlshortener.orchestrator.core.WorkflowEngine;
import com.urlshortener.orchestrator.policy.PolicyEngine;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;

/** Wires context, audit, policy, human channel and engine for one scenario run, then writes the report. */
public final class ScenarioRunner {

    private final ScenarioEnvironment env;
    private final EngineConfig config;
    private final Clock clock;

    public ScenarioRunner(ScenarioEnvironment env, EngineConfig config, Clock clock) {
        this.env = env;
        this.config = config;
        this.clock = clock;
    }

    /**
     * @param outDir run folder for audit.jsonl, report.json, SUMMARY.md and artifacts; null keeps everything in memory
     */
    public RunReport run(Scenario scenario, ApprovalGateway human, Path outDir) throws IOException {
        String runId = WorkflowEngine.newRunId(scenario.name());
        WorkflowContext ctx = new WorkflowContext(runId, scenario.requirement(), clock);
        try (AuditLog audit = new AuditLog(runId, clock, outDir == null ? null : outDir.resolve("audit.jsonl"))) {
            WorkflowEngine engine = new WorkflowEngine(scenario.name(), scenario.plan(env), ctx,
                    PolicyEngine.defaults(), human, audit, config);
            RunReport report = engine.run();
            if (outDir != null) {
                ReportWriter.write(report, outDir);
            }
            return report;
        }
    }
}
