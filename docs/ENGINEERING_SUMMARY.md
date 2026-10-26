# Engineering summary

One-page summary in the shape the brief asks for (§4.8): plan and rationale, artifacts, risks, trade-offs and validation, assumptions, limitations. Details live in the linked documents.

## Problem, normalised

Build a URL shortener with core APIs, analytics and reliability features, **and** an agentic system that can take such requirements (clear, brownfield or ambiguous) through the SDLC with controlled autonomy: explicit dependency graph, gates, parallel paths, context and lineage, human approvals, bounded retries, fallback, rollback, safe stop, policy guardrails, audit-grade traceability, reliability metrics and dynamic re-planning.

## Plan and rationale

| # | Task | Depends on | Rationale |
|---|---|---|---|
| 1 | Service core: schema (Flyway), entities, create/redirect, validation | none | Everything else builds on a correct, safe core. |
| 2 | Analytics (async, atomic counter), expiry, aliases, soft delete | 1 | Product features; analytics designed so it can never break redirects. |
| 3 | Reliability: rate limiting, cache, problem+json, health/metrics, OpenAPI | 1 | Operability and abuse resistance. |
| 4 | Service tests (unit + full-stack integration) | 1-3 | Evidence the orchestrator can consume. |
| 5 | Orchestrator core: graph, engine, context, gates, retry, approvals | none (parallel with 1-4) | The critical differentiator; independent of the product. |
| 6 | Guardrails, audit chain, metrics, re-planning, rollback, safe stop | 5 | Governance on top of execution. |
| 7 | Stage agents incl. impact analysis on the real service code | 4, 6 | Ties the orchestrator to the product it governs. |
| 8 | Three scenarios + CLI + reports | 7 | Deliverable demonstrations. |
| 9 | Engine, policy, scenario and CLI tests; documentation; CI | 8 | Validation and handover. |

Design rationale in brief: the service favours redirect latency and availability (cache, async best-effort analytics, 302 + no-store for correctness of analytics and deletes). The orchestrator separates *proposing* (agents) from *committing* (a single-writer engine), so every output passes gates, policy and, where needed, a human before anything depends on it; versioned, hashed artifacts make re-planning and lineage precise. See [ARCHITECTURE.md](ARCHITECTURE.md).

## Artifacts

| Artifact | Location |
|---|---|
| Service code, schema, config | `shortener-service/` (Flyway `V1__create_short_url_tables.sql`, `application.yml`) |
| API definition | Live OpenAPI at `/v3/api-docs`; [API.md](API.md) |
| Orchestrator code | `orchestrator/` |
| Tests (85) | `*/src/test/`; [TESTING.md](TESTING.md) |
| Scenario outputs | [sample-runs/](sample-runs) (`SUMMARY.md` + `audit.jsonl` each); every CLI run also writes `report.json` and all artifact versions |
| Documentation | README and `docs/` |
| CI | `.github/workflows/ci.yml` (build, test, run scenarios, verify audit chains) |

## Validation

- `mvn verify`: 49 service tests (unit + integration on the real schema) and 36 orchestrator tests (engine controls, policy, scenarios, CLI), all passing.
- Manual smoke test of the packaged service: create with alias → 201, redirect → 302 with `no-store`, stats show the click and referrer, unsafe URL → 400 problem+json, health UP, Swagger UI served.
- All three scenarios succeed end to end with intact audit chains; failure paths (`--fail`, `--reject`, `--tests plan`, `--max-attempts`, kill switch, tampered audit) behave as specified ([SCENARIOS.md](SCENARIOS.md)).

## Risks and trade-offs (top items)

| Risk / trade-off | Mitigation or rationale |
|---|---|
| Analytics loss on crash (async) | Accepted: redirects first; bounded queue, caller-runs under overload, failure metric. |
| Per-node rate limit and cache | Fine for one node; Redis for both when scaling out. |
| Abuse of public create endpoint | Validation allow-lists, token bucket; edge protection and auth for production. |
| Deterministic agents | Reproducible and testable; LLM agents can plug into `Agent` and stay governed. |
| Agents propose change sets, not code | Humans own source changes; policy reasons over declarative change sets. |
| No per-agent timeout | Run deadline bounds it today; timed futures are a small follow-up. |

Full list: [RISKS_AND_TRADEOFFS.md](RISKS_AND_TRADEOFFS.md).

## Assumptions

Single tenant, no authentication; small-service traffic on one node; referrer and user agent acceptable analytics data, IPs not stored; release readiness means a reviewed candidate, deployment stays a human action; scripted approvers stand in for people in unattended runs.

## Limitations

No LLM in the loop; implementation agent does not edit code; suite-level test evidence; no per-agent timeout; runs are not resumable after a crash; single-node caching and rate limiting; click-cap and unique-visitor features exist as orchestrated plans, not shipped code.

## Suggested next steps

1. Implement the click-cap feature for real from the brownfield run's change set and plan (it is the natural "brownfield" PR).
2. PostgreSQL via Testcontainers in CI; Redis-backed rate limiting and cache for horizontal scale.
3. Per-agent timeouts and resumable runs from the persisted audit trail and artifacts.
4. An LLM-backed design or implementation agent behind the existing gates and policies, with the deterministic agents kept as fallbacks.
