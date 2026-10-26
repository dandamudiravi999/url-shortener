# Orchestration

This document explains how the orchestrator turns a requirement into a reviewable engineering outcome, and how each control required by the brief is implemented. Class names refer to `orchestrator/src/main/java/com/urlshortener/orchestrator/`.

## Mapping to the brief

| Requirement (brief §4) | Where it lives | How to see it |
|---|---|---|
| Requirement understanding, ambiguity | `RequirementsAgent`, `RequirementAnalyzer` | `requirements` artifact: FRs with acceptance criteria, NFRs, open questions, assumptions. Ambiguous scenario. |
| Task decomposition with dependencies | `WorkflowGraph`, `WorkflowNode`, `Scenario.plan` | "Plan" section and mermaid graph in every `SUMMARY.md`. |
| Codebase reasoning (brownfield) | `ImpactAnalysisAgent` | `impact-analysis` artifact: impacted files by layer, endpoints, data flow, next migration. Brownfield scenario. |
| Explicit dependency graph | `WorkflowGraph` (validated DAG, Kahn ordering) | `PLAN_CREATED` audit event; `finalPlan` in `report.json`. |
| Entry/exit gates | `Gate`, `WorkflowNode.entryGates/exitGates` | `ENTRY_GATE_FAILED`, `EXIT_GATE_FAILED` events. |
| Sequential and parallel paths, synchronization | Engine scheduler (`scheduleReady`), worker pool | Parallel levels in the plan; join nodes such as `release-readiness` wait for every branch. |
| Cross-stage context, decision lineage | `WorkflowContext`, `Artifact`, `DecisionRecord` | "Decision lineage" in `SUMMARY.md`: each decision lists the artifact versions it was based on. |
| Human approval for high-impact actions | `Impact.HIGH`, `ApprovalGateway`, policy REQUIRE_APPROVAL | "Human oversight" in `SUMMARY.md`; `APPROVAL_*` events. |
| Bounded retries | `RetryPolicy` (max 10, exponential backoff, capped) + global attempt budget | `RETRY_SCHEDULED` events; greenfield testing node. |
| Fallback | `WorkflowNode.fallback` | `FALLBACK_STARTED`; brownfield implementation node. |
| Rollback | `Compensation`, reverse completion order | `ROLLBACK_STARTED`, `NODE_ROLLED_BACK`; `--fail` or `--reject`. |
| Safe stop | Kill switch file, deadline, attempt budget, re-plan limit | `SAFE_STOP`; `touch .orchestrator/STOP` or `--max-attempts`. |
| Policy guardrails: security, compliance, change control | `policy.*` | "Policy guardrails" in `SUMMARY.md`; `POLICY_EVALUATED`, `POLICY_DENIED`. |
| Audit-grade observability and traceability | `AuditLog` (hash-chained JSONL) | `audit.jsonl`; `verify-audit`. |
| Reliability metrics | `RunMetrics` | "Reliability metrics" in `SUMMARY.md`; `metrics` in `report.json`. |
| Dynamic re-planning on upstream change | `WorkflowEngine.invalidateConsumers`, `applyPlanChange` | `REPLANNED`, `PLAN_EXTENDED`; ambiguous scenario. |
| Controlled autonomy | Autonomy boundaries below | Every approval and denial in the audit trail. |

## Autonomy boundaries

What agents **may** do on their own:

- Read a consistent snapshot of the context (all committed artifacts, decisions, facts).
- Propose artifacts, decisions and plan extensions.
- Retry transient failures within the node's retry policy and the run's attempt budget.
- Ask the human clarifying questions (audited).
- Escalate: request an approval the rules would not otherwise require.

What agents **may not** do:

- Write shared state. Outputs are committed by the engine only after exit gates, policy and (if needed) approval.
- Remove nodes, edges or gates. Plan changes can only **add** work, and only gate nodes that have not started.
- Bypass a DENY. A denied output is never committed and never retried with the same agent; only a fallback agent (which is checked by the same rules) can continue.
- Approve their own work. HIGH impact, policy REQUIRE_APPROVAL and agent escalations all go to the `ApprovalGateway`.
- Exceed budgets. Attempt budget, deadline and re-plan limit end the run with a safe stop.

What humans own: answers to clarifications, approval of schema changes, protected files, deletions, large change sets, personal-data handling without mitigation, and every release candidate.

## The plan: dependency graph with gates

A `WorkflowNode` is static configuration:

```java
WorkflowNode.builder("implementation", StageType.IMPLEMENTATION, new ImplementationAgent(IN_PLACE_SCHEMA_EDIT))
        .fallback(new ImplementationAgent(ADDITIVE_MIGRATION))
        .dependsOn("design")
        .entryGate(Gate.requiresArtifacts("design"))
        .impact(Impact.MEDIUM)
        .retry(RetryPolicy.standard())          // 3 attempts, 50 ms * 2^n, capped at 2 s
        .compensation(Compensations.discardBranch())
        .build();
```

- **Entry gates** check preconditions against the snapshot before the agent runs. A failing entry gate fails the node without retry (the inputs will not change by retrying).
- **Exit gates** check the agent's *staged* outputs (proposed artifacts overlaid on the snapshot). A failing exit gate counts as a retryable failed attempt, because a new attempt may produce better output.
- `WorkflowGraph` rejects unknown dependencies, duplicate ids and cycles on every change, including run-time `addDependency`, which is rolled back if it would create a cycle.

## Execution: parallel, stateful, non-linear

The engine schedules every node whose dependencies all `SUCCEEDED`, up to `maxParallelism` (default 4). Nodes with no dependency between them run concurrently; a node with several dependencies is the synchronization point. The greenfield plan, for example:

```
Level 0: requirements
Level 1: design
Level 2: implementation, documentation        <- parallel
Level 3: testing, security-review             <- parallel (security-review added at run time)
Level 4: release-readiness                    <- join
```

Execution is non-linear in three ways:

1. **Plan extension.** An agent returns `PlanChange(node, gates, reason)`. The design agent does this when the design exposes a public write endpoint or handles personal data: it inserts `security-review` after `implementation` and makes `release-readiness` depend on it. The engine validates the change, bumps the plan revision and records `PLAN_EXTENDED` with the new parallel levels.
2. **Re-planning on upstream change.** Each execution records the artifact versions it consumed. When a commit changes an artifact's content (new hash, new version), every node that consumed an older version is stale. Completed stale nodes and everything downstream return to `PENDING`; a stale node that is still running has its outputs discarded when it finishes and is re-queued. One `REPLANNED` event records the change, the stale consumers and the new plan revision. Identical content is not a change, so re-running a node that produces the same output does not cascade.
3. **Recovery paths.** Retry, fallback, approval rejection, fail-fast with downstream skipping, and compensation all change the path the run takes.

A re-plan limit (`maxReplans`, default 5) stops a plan that keeps invalidating itself.

## Governance per node

After the agent (or fallback) produces outputs that pass exit gates, the `PolicyEngine` evaluates them. Rules return findings with a verdict; the strictest verdict wins; a rule that throws is a DENY (fail closed).

| Rule | Category | DENY when | REQUIRE_APPROVAL when |
|---|---|---|---|
| `SEC-001-no-secrets` | Security | Any proposed artifact contains an AWS key, private key block, GitHub token or hard-coded credential assignment. | |
| `CHG-001-change-control` | Change control | The change set modifies or deletes an applied Flyway migration (`db/migration/V*__*.sql`). | A new migration; a deletion; `pom.xml`, `application.yml`, `.github/workflows/`, `Dockerfile`; more than 25 files. |
| `CMP-001-personal-data` | Compliance | | An artifact stores IP addresses, emails or location without stating retention or anonymisation. |
| `CMP-002-release-evidence` | Compliance | Release proposed without a `test-report` with `status: PASSED`, without documentation or requirements, or with a failed security review. | Always, for release readiness. |

Then approval is requested if the node is HIGH impact (or `requiresApproval`), any finding is REQUIRE_APPROVAL, or the agent escalated. The request carries the reasons and evidence (summary, agent, short hashes of each proposed artifact). Rejection marks the node `REJECTED`, which fails the run.

## Failure handling

| Mechanism | Behaviour |
|---|---|
| Retry | Per node `RetryPolicy`: bounded attempts (1 to 10), exponential backoff with a cap. Exceptions and `AgentResult.failure(..., true)` are retryable; `NonRetryableException`, `failure(..., false)`, policy DENY and entry gate failures are not. |
| Attempt budget | `maxTotalAttempts` across the whole run (default 40). Exceeding it requests a safe stop. |
| Fallback | If the primary agent's attempts end in failure (not a stop), the fallback agent gets one attempt through the same gates and policy. |
| Fail fast | A failed or rejected node marks its downstream nodes `SKIPPED`; no new nodes start; in-flight nodes finish. |
| Rollback | Completed nodes with a `Compensation` are compensated in reverse completion order (`ROLLED_BACK`). A failing compensation is recorded as `ROLLBACK_FAILED` (manual intervention) and the rest still run. |
| Safe stop | Kill switch file, deadline, attempt budget or re-plan limit: nothing new starts, workers stop before their next attempt, pending nodes become `CANCELLED`. No rollback, so a human can inspect the preserved state. |

## Observability

### Audit trail

`audit.jsonl` holds one JSON object per line:

```json
{"seq":12,"at":"2026-10-24T15:41:02.118Z","runId":"brownfield-3f9c1a2b","nodeId":"implementation",
 "type":"POLICY_DENIED","message":"policy denied: [MODIFY .../V1__create_short_url_tables.sql: applied migrations are immutable, add a new V<n>__ migration]",
 "data":{},"prevHash":"5b1e...","hash":"c07a..."}
```

`hash = sha256(prevHash + canonical JSON of the event without hash)`, starting from 64 zeros. `java -jar orchestrator.jar verify-audit <file>` recomputes the chain and reports the first broken event; editing, deleting or reordering any line is detected. The file is flushed per event.

Event types cover the whole lifecycle: run start and finish, plan created and extended, node started, every attempt, gate failures, retries, fallback, policy evaluation and denial, approval and clarification requests and answers, artifact publication (with version and hash), decisions (with rationale and inputs), node success, failure and skip, re-plans, rollback and safe stop.

### Metrics

| Metric | Definition |
|---|---|
| Success rate | Succeeded nodes / (succeeded + failed nodes). |
| Attempts, retries, retry rate | All agent attempts; retries scheduled; retries / attempts. |
| Fallbacks, rollbacks | Fallback activations; compensated nodes. |
| Re-plans, plan extensions | Upstream-change invalidations; run-time node insertions. |
| Approvals requested / rejected; policy denials | Governance load and outcomes. |
| MTTR | Mean time from a node's first failed attempt to its eventual success, over recovered nodes. |
| End-to-end latency; per-node latency | Run wall-clock; time spent per node (summed across re-runs). |

### Reports

Every run writes `SUMMARY.md` (the engineering summary: requirement, plan and graph, execution table, metrics, human oversight, policy findings, decision lineage, artifacts with hashes, assumptions, limitations), `report.json` (the same data, machine-readable) and `artifacts/<name>.v<n>.md` for every version of every artifact.

## Extending

- **A new agent**: implement `Agent.execute(AgentTask)`; read from `task.context()`; return `AgentResult.success(...)` with artifacts and decisions. Throw `NonRetryableException` for deterministic failures. An LLM-backed agent fits here unchanged.
- **A new policy**: implement `PolicyRule` and add it to `PolicyEngine.defaults()`.
- **A new scenario**: implement `Scenario` (requirement, plan, scripted human) and register it in `Scenarios.all()`.
- **A real approval channel**: implement `ApprovalGateway` (chat, ticketing, PR review). It must return a rejection when it cannot reach a person.
