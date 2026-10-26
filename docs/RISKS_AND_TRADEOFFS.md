# Risks, trade-offs and limitations

## Failure scenarios and guardrails

### Service

| Failure scenario | Impact | Guardrail | Residual risk |
|---|---|---|---|
| Code collision between concurrent creates | Two links with one code | Unique constraint + catch and retry with a fresh code, bounded attempts, 503 if exhausted, `generation_exhausted` metric | None for correctness; alert if the metric moves (keyspace pressure). |
| Concurrent clicks on one link | Lost counts | Single atomic `UPDATE ... SET click_count = click_count + 1` | None. |
| Analytics store slow or failing | Slow or failing redirects | Clicks recorded off the request thread; failures logged and counted, never surfaced to the user | Events still queued at a crash are lost (accepted: analytics are best-effort). |
| Analytics executor saturated | Memory exhaustion or dropped events | Bounded queue (10k) with caller-runs | Under extreme load redirects slow down instead. |
| Abuse of create endpoint (spam, storage fill) | Cost, reputation | Per-client token bucket, 429 + `Retry-After`; bounded bucket cache | Distributed attackers across many IPs; needs edge protection / auth in production. |
| Shortening `javascript:` / internal URLs / self links | XSS-style payloads, phishing internal hosts, redirect loops | Scheme allow-list, private ranges and `.local` blocked, self-host blocked, credentials in URL blocked | Public hosts that resolve to private IPs (DNS rebinding) are not checked, by design (no DNS lookups; the service never fetches targets). |
| Link enumeration | Privacy leak of other users' links | Random Base62 codes from `SecureRandom` | Custom aliases are guessable by nature. |
| Deleted or expired link still served from cache | Wrong redirect | Delete evicts; expiry is checked on every cached hit | None. |
| Stale browser caches after delete/expiry | Users still redirected | `302` with `Cache-Control: no-store` | Intermediaries that ignore `no-store`. |
| Bad configuration (e.g. code length 2) | Collisions or crash at runtime | `@Validated` properties fail startup | None. |
| Schema drift between code and DB | Runtime errors | Flyway owns the schema; Hibernate `validate` fails startup on mismatch | None. |

### Orchestrator

| Failure scenario | Guardrail |
|---|---|
| Agent produces wrong or incomplete output | Exit gates on staged outputs; outputs never committed until gates pass; bounded retries. |
| Agent produces unsafe output (secret, migration edit) | Policy DENY: not committed, not retried with the same agent. |
| Agent loops or burns budget | Per-node retry bound (max 10) and run-wide attempt budget → safe stop. |
| Run hangs | Run deadline → safe stop. (Per-agent timeouts are a known gap; see limitations.) |
| Plan oscillates (re-plans keep invalidating each other) | Re-plan limit → safe stop. |
| Agent tries to weaken the plan | Plan changes can only add nodes and gate not-yet-started nodes; every change is validated for cycles and audited. |
| Human channel unavailable | Treated as rejection (fail closed). |
| Partial side effects after a late failure | Compensation in reverse completion order; failures of compensation are recorded for manual action. |
| Operator needs to stop now | Kill switch file; nothing new starts, state preserved. |
| Audit tampering or loss | Hash chain + `verify-audit`; flush per event; audit write failure aborts instead of continuing unaudited. |
| Race between parallel nodes and upstream changes | Single-writer coordinator; agents read immutable snapshots; consumed versions recorded per execution; running stale nodes discarded and re-queued. |

## Trade-offs

| Choice | Benefit | Cost |
|---|---|---|
| 302 over 301 | Accurate analytics, instant delete/expiry | Extra hop for repeat visitors, more load on the service |
| Random codes over a sequence/hash | Non-enumerable, no coordination | Collision handling; codes are not deterministic per URL (de-duplication compensates for permanent links) |
| Best-effort async analytics | Redirect latency and availability | Small potential loss of click events |
| In-process cache and rate limiter | Zero infrastructure, very fast | Per-node state; horizontal scaling needs Redis (or similar) for both |
| H2 by default | Runs anywhere with one command | Not production storage; PostgreSQL recommended (same migrations) |
| Soft delete | History and code-reuse safety | Table only grows; needs a purge/archival job eventually |
| Deterministic, rule-based agents | Reproducible, testable governance; no API keys; the engine is what is being evaluated | Requirements understanding is limited to a capability catalogue; no free-form code generation |
| Agents propose change sets, do not edit code | Humans stay in control of source changes; policy can reason about a declarative change set | The brownfield and ambiguous runs do not produce the feature code itself |
| Single coordinator thread | Simple, race-free state management | The coordinator is a bottleneck only at very high node counts (not a concern for SDLC graphs of tens of nodes) |
| Safe stop does not roll back | Operators can inspect and resume decisions | Completed side effects stay until a human acts |
| JSONL audit file per run | Simple, portable, verifiable | No central query; ship to a log platform for cross-run analysis |

## Assumptions

- Single-tenant service without user accounts; anyone who can reach the API can create links and read stats for a known code (authentication is out of scope per the brief's focus).
- Traffic in the "small service" range (thousands of links per day, hundreds of redirects per second) on one node; the design keeps the app tier stateless apart from caches so it can scale out.
- Referrer and user agent are acceptable analytics data; IP addresses are not stored.
- "Release readiness" means a reviewed release candidate, not an automatic production deploy. Deployment is a human action outside the orchestrator.
- The scripted approvers in unattended runs stand in for real people; `--interactive` (or a custom `ApprovalGateway`) puts a real person in the loop.

## Limitations

- **No LLM in the loop.** Agents are deterministic. The `Agent` interface is the integration point; an LLM agent would remain bound by gates, policy, budgets and approvals.
- **Change sets, not code.** The implementation agent outputs a change set and per-file plan; it does not modify source files or open pull requests.
- **Test evidence is suite-level.** The test agent reads or produces Surefire results for the whole service; it does not map individual tests to acceptance criteria beyond the generated test plan.
- **No per-agent timeout.** A hung agent is bounded only by the run deadline (default 15 minutes). Wrapping agent calls in a timed future is a straightforward follow-up.
- **Resumability.** State is in memory for the duration of a run; the audit trail and artifacts are persisted, but a crashed run cannot be resumed from them yet.
- **Single node.** Rate limiting and caching are per instance.
- **Click-cap and unique-visitor features** exist as orchestrated plans and change sets in the sample runs, not as shipped service code.
