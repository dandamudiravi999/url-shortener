# Testing

## How to run

```bash
mvn verify                                   # everything: 85 tests, both modules
mvn -pl shortener-service test               # service only
mvn -pl orchestrator test                    # orchestrator only
mvn -pl shortener-service test -Dtest=ShortUrlApiIntegrationTest   # one class
```

Reports land in `*/target/surefire-reports/`. The orchestrator's test agent reads the service's reports as release evidence, which is why `mvn verify` comes before `java -jar orchestrator/target/orchestrator.jar run all` in the quick start.

CI ([`.github/workflows/ci.yml`](../.github/workflows/ci.yml)) runs `mvn verify`, then all three scenarios with the CLI, then `verify-audit` on every produced audit trail, and uploads the run folders.

## Strategy

| Level | Purpose | Tools |
|---|---|---|
| Unit | Business rules in isolation, every edge case, fast. Time and randomness are injected (`Clock`, `CodeGenerator`) so tests never sleep or depend on luck. | JUnit 5, AssertJ, Mockito |
| Integration (service) | The real stack: HTTP → controller → service → JPA → H2 with the **real Flyway migration**, error mapping, headers, cache eviction, actuator and OpenAPI. Analytics run synchronously in the `test` profile so assertions are deterministic. | `@SpringBootTest` + MockMvc |
| Engine behaviour (orchestrator) | Each governance control proven with small stub agents: parallelism, retries, fallback, gates, policy, approvals, re-planning, plan extension, rollback, safe stop, audit lineage. | JUnit 5, latches for real concurrency |
| Scenario (end to end) | The three assessment scenarios against the real `shortener-service` sources, with fixture Surefire evidence so the result does not depend on build order. | `ScenarioRunner` |
| CLI | Usage, exit codes, a full run with refused release, audit verification of the written file. | `OrchestratorCli` with captured output |

## Inventory

### shortener-service (49 tests)

| Class | Tests | Covers |
|---|---|---|
| `UrlValidatorTest` | 16 | Normalisation; rejects non-http(s), `javascript:`, missing scheme or host, embedded credentials, localhost, 127/10/172.16-31/192.168 ranges, `.local`, configured blocked hosts, self-reference, over-long URLs, garbage. |
| `ShortUrlServiceTest` | 10 | Collision retry, race on insert (unique-constraint violation) retried, bounded give-up, de-duplication, alias conflict, TTL conversion, invalid expiry combinations, redirect records click, expired link is 410 and not counted, unknown code is 404. |
| `AliasValidatorTest` | 8 | Valid alias; too short, spaces, slashes, dots, reserved names (case-insensitive). |
| `Base62CodeGeneratorTest` | 3 | Length and alphabet, uniqueness over 10k codes, invalid length. |
| `TokenBucketTest` | 2 | Burst then refill, never above capacity. |
| `ShortUrlApiIntegrationTest` | 9 | Create → redirect → stats (clicks, per-day, referrers); `Location` and `shortUrl`; de-duplication 201 then 200; alias and 409; problem+json for unsafe URL, blank URL, negative TTL; 404s; expiry 410; delete after cache warm-up then 404; health and OpenAPI. |
| `RateLimitIntegrationTest` | 1 | Third create within the burst gets 429 with `Retry-After`; reads unaffected. |

### orchestrator (36 tests)

| Class | Tests | Covers |
|---|---|---|
| `WorkflowEngineTest` | 13 | Parallel branches really run concurrently and join; bounded retry and MTTR; exhausted retries fail fast, skip downstream and roll back in reverse order; non-retryable failure goes straight to fallback; exit-gate failures retried and never committed; secrets denied by policy and not committed; HIGH impact rejected → rollback; broken approval channel = reject; upstream change re-runs stale consumer; agent plan extension gates downstream; kill switch safe stop; attempt budget safe stop; hash chain and decision lineage. |
| `PolicyRulesTest` | 6 | Secret patterns; immutable migrations, approval for new migrations, protected paths and deletions; blast-radius threshold; personal data with and without retention; release evidence and mandatory sign-off; throwing rule fails closed. |
| `ScenarioIntegrationTest` | 5 | Greenfield (retry, security review inserted, evidence); brownfield (DENY → fallback → additive V2, approval); ambiguous (requirements v2, re-plan, impact re-run, visitor hash, security review); ambiguous with no answers fails safely; forced failure rolls back. |
| `RequirementAnalyzerTest` | 4 | Clear requirement → capabilities, no questions; vague terms → questions and assumptions; answers resolve ambiguity and add scope; capability ids round-trip through markdown. |
| `WorkflowGraphTest` | 3 | Topological order and parallel levels; unknown deps and duplicates; cycle-creating edge rejected and rolled back. |
| `ImpactAnalysisAgentTest` | 2 | Against the real repo: click-cap is new, next migration V2, expected files and the redirect endpoint found, unrelated files not; missing codebase is non-retryable. |
| `OrchestratorCliTest` | 2 | `list` and usage exit codes; `--tests plan` run exits 1 with the release denial, and its audit file verifies. |
| `AuditLogTest` | 1 | Persisted chain verifies; an edited line and a deleted line are both detected at the right event. |

## What is deliberately not covered

- **Load and latency.** The p99 targets in the ambiguous scenario are requirements captured by the orchestrator, not benchmarks run here. A Gatling or k6 run against a PostgreSQL-backed deployment would be the next step.
- **PostgreSQL.** Migrations use portable SQL and H2 runs in PostgreSQL mode, but tests run on H2 only. Testcontainers would close this gap.
- **Async analytics under load.** Tests use the synchronous executor; the bounded queue and caller-runs fallback are reviewed by design rather than load-tested.
- **Interactive console approvals.** `ConsoleApprovalGateway` is exercised manually (`--interactive`); its "EOF or I/O error means reject" path is simple enough to review.
- **The proposed brownfield code.** The implementation agent proposes change sets; it does not write the click-cap or visitor-hash code, so there is nothing new to test for those features.
