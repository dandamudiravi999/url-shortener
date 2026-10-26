# Sample runs

Real output of:

```bash
mvn verify
java -jar orchestrator/target/orchestrator.jar run all
```

Each folder holds the run's `SUMMARY.md` (engineering summary) and `audit.jsonl` (hash-chained audit trail). A local run also writes `report.json` and `artifacts/<name>.v<n>.md` for every artifact version; those are left out here to keep the repository small.

Check that a trail is untouched:

```bash
java -jar orchestrator/target/orchestrator.jar verify-audit docs/sample-runs/brownfield/audit.jsonl
```
