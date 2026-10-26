# URL Shortener with an Agentic SDLC Orchestrator

A production-style URL shortener in **Java 21 / Spring Boot 3**, plus a governed **agentic orchestration layer** that takes a requirement (clear, brownfield or ambiguous) through requirements, design, implementation, testing, security review, documentation and release readiness, with dependency graphs, gates, human approvals, policy guardrails, retries, rollback, re-planning and a tamper-evident audit trail.

| Module | What it is |
|---|---|
| [`shortener-service`](shortener-service) | REST service: shorten, redirect, click analytics, custom aliases, expiry, soft delete, rate limiting. Flyway schema, H2 by default, OpenAPI docs, health and metrics. |
| [`orchestrator`](orchestrator) | Plain-Java library + CLI that plans and executes SDLC work as a DAG of agents under explicit autonomy boundaries. Ships the three assessment scenarios. |

```
url-shortener/
├── pom.xml                      parent build (Spring Boot 3.3 BOM, Java 21)
├── shortener-service/           the product
├── orchestrator/                the agentic SDLC system
├── docs/                        architecture, API, orchestration, scenarios, testing, risks, summary
│   └── sample-runs/             real output of `run all` (SUMMARY.md + audit.jsonl per scenario)
└── .github/workflows/ci.yml     build, test, run all scenarios, verify audit chains
```

## Quick start

**Prerequisites:** JDK 21 and Maven 3.9+ (`java -version`, `mvn -v`). Nothing else: the database is in-memory H2.

```bash
# 1. Build and run every test (85 tests across both modules)
mvn verify

# 2. Start the service on http://localhost:8080
java -jar shortener-service/target/shortener-service-1.0.0-SNAPSHOT.jar
#    (or: mvn -pl shortener-service spring-boot:run)

# 3. Run the three orchestrator scenarios (scripted human approvals)
java -jar orchestrator/target/orchestrator.jar run all
```

Each orchestrator run writes `runs/<timestamp>-<scenario>/` with `SUMMARY.md` (the engineering summary), `report.json`, `audit.jsonl` and every artifact version.

## Using the service

```bash
# Shorten (optionally: "alias", "ttlSeconds" or "expiresAt")
curl -s -X POST localhost:8080/api/v1/urls \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/articles/2026/10/how-we-scaled","alias":"launch-2026"}'
# -> 201 {"code":"launch-2026","shortUrl":"http://localhost:8080/launch-2026",...}

# Follow it
curl -i localhost:8080/launch-2026          # -> 302 Location: https://example.com/...

# Analytics
curl -s localhost:8080/api/v1/urls/launch-2026/stats
# -> {"totalClicks":1,"clicksByDay":[{"date":"2026-10-24","clicks":1}],"topReferrers":[...],...}

# Delete (soft): redirects stop immediately, the code is never reused
curl -i -X DELETE localhost:8080/api/v1/urls/launch-2026   # -> 204
```

Interactive API docs: <http://localhost:8080/swagger-ui.html>. Health: `/actuator/health`. Metrics: `/actuator/metrics/shortener.redirects`. Full reference: [docs/API.md](docs/API.md).

## Using the orchestrator

```bash
java -jar orchestrator/target/orchestrator.jar list
java -jar orchestrator/target/orchestrator.jar run greenfield
java -jar orchestrator/target/orchestrator.jar run brownfield --interactive   # you are the approver
java -jar orchestrator/target/orchestrator.jar run ambiguous --tests run      # really runs the test suite
java -jar orchestrator/target/orchestrator.jar verify-audit runs/<run>/audit.jsonl
```

| Option | Effect |
|---|---|
| `--interactive` | Approvals and clarification questions are asked on the terminal instead of using the scenario's scripted human. |
| `--tests auto\|run\|reports\|plan` | Where test evidence comes from. `auto` (default) reads Surefire reports from the last build if present, otherwise runs Maven. `plan` produces no evidence, so release policy refuses the release. |
| `--fail <node>` | Force a node to fail: shows fail-fast, downstream skipping and reverse-order rollback. |
| `--reject <node>` | The scripted human rejects that node's approval: shows rejection handling and rollback. |
| `--max-attempts <n>` | Global attempt budget; exceeding it triggers a safe stop. |
| `touch .orchestrator/STOP` | Kill switch: a running workflow finishes in-flight work, starts nothing new, and stops. |

Exit code is `0` only when every run succeeded, which makes it usable as a CI gate.

### The three scenarios

| Scenario | Requirement | What it demonstrates |
|---|---|---|
| **greenfield** | Build the shortener (shorten, redirect, aliases, expiry, analytics, delete, rate limit). | Parallel branches, design agent inserts a security review at run time, injected transient test failure recovered by retry (MTTR), approval gates. |
| **brownfield** | Add a per-link click limit to the existing service. | Impact analysis over the **real** `shortener-service` sources, policy DENY when the primary agent edits an applied Flyway migration, automatic fallback to an additive `V2` migration, schema-change approval. |
| **ambiguous** | "Make the short links faster and more reliable, and give us better analytics." | Ambiguity detection, optimistic progress on documented assumptions, a human clarifies in parallel, requirements change to v2 and the engine re-plans stale work; personal-data design pulls in a security review. |

Walk-throughs: [docs/SCENARIOS.md](docs/SCENARIOS.md). Real outputs: [docs/sample-runs](docs/sample-runs).

## Documentation

| Document | Contents |
|---|---|
| [Architecture](docs/ARCHITECTURE.md) | Components of both modules, orchestration model, control flow, threading, key decisions. |
| [API reference](docs/API.md) | Endpoints, request and response schemas, error model, examples. |
| [Orchestration](docs/ORCHESTRATION.md) | Graph, gates, retries, fallback, approvals, policy, audit, metrics, re-planning, rollback, safe stop; mapping to each requirement of the brief. |
| [Scenarios](docs/SCENARIOS.md) | Decomposition, orchestration and validation for greenfield, brownfield and ambiguous. |
| [Testing](docs/TESTING.md) | Strategy, test inventory, how to run, what is and is not covered. |
| [Risks and trade-offs](docs/RISKS_AND_TRADEOFFS.md) | Failure scenarios, guardrails, trade-offs, assumptions, limitations. |
| [Engineering summary](docs/ENGINEERING_SUMMARY.md) | Plan and rationale, artifacts, risks, validation, assumptions, limitations in one page. |

## Configuration (service)

All settings live in [`application.yml`](shortener-service/src/main/resources/application.yml) and can be overridden with environment variables (Spring relaxed binding), for example:

| Variable | Default | Meaning |
|---|---|---|
| `SHORTENER_BASE_URL` | `http://localhost:8080` | Prefix used to build `shortUrl` in responses. |
| `SHORTENER_CODE_LENGTH` | `7` | Length of generated Base62 codes (4 to 16). |
| `SHORTENER_RATE_LIMIT_CAPACITY` | `20` | Burst of create requests per client. |
| `SHORTENER_RATE_LIMIT_REFILL_PER_MINUTE` | `60` | Sustained create requests per client per minute. |
| `SHORTENER_ANALYTICS_ASYNC` | `true` | Record clicks off the redirect path. |
| `SPRING_DATASOURCE_URL` | in-memory H2 | e.g. `jdbc:h2:file:./data/shortener` for persistence. PostgreSQL works with the same migrations once the `org.postgresql:postgresql` driver is added. |
