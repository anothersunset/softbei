## Scope

- What changed:
- Why it changed:
- Risk area: pipeline / guard / RCA / executor / frontend / docs

## Verification

- [ ] Unit or integration tests added/updated
- [ ] `mvn test` or targeted Maven command attached
- [ ] Frontend smoke checked at `http://127.0.0.1:8080/` when UI changed
- [ ] Mock/real LLM boundary is stated if behavior depends on provider
- [ ] dry-run/live execution behavior is stated if commands can mutate state

## Safety Review

- [ ] Dangerous command paths still go through guard decisions
- [ ] REVIEW actions still require explicit confirmation
- [ ] Rollback or manual recovery path is documented for mutating changes
- [ ] Logs/audit/traces avoid secrets and API keys

## Evidence

- Screenshots, curl output, or report links:
- Open questions for reviewer:
