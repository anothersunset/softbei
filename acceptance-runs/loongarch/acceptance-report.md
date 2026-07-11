# OpsGuard VM Acceptance Report

- Time: 2026-07-07T13:14:35+08:00
- Repo: https://github.com/anothersunset/softbei.git
- Branch: main
- Base URL: http://127.0.0.1:18080
- Report dir: /home/vmuser/softbei-main-latest/softbei-main/acceptance-runs/loongarch/report-20260707-131327
- Commit: unknown
- Kernel: Linux 6.6.0-32.7.v2505.ky11.loongarch64 loongarch64 GNU/Linux
- Java: openjdk version "17.0.18" 2026-01-20
- Maven: Apache Maven 3.6.3 (kylin 3.6.3-2)

## Summary

- PASS: 73
- FAIL: 0
- SKIP: 1
- TOTAL: 74

## Checks

| ID | Check | Status | Detail |
| --- | --- | --- | --- |
| ENV-01 | command:bash | PASS | /usr/bin/bash |
| ENV-02 | command:git | PASS | /usr/bin/git |
| ENV-03 | command:java | PASS | /usr/lib/jvm/java-17-openjdk-17.0.18.8-8.p01.ky11.loongarch64/bin/java |
| ENV-04 | command:mvn | PASS | /usr/bin/mvn |
| ENV-05 | command:curl | PASS | /usr/bin/curl |
| ENV-06 | command:jq | PASS | /usr/bin/jq |
| ENV-07 | command:timeout | PASS | /usr/bin/timeout |
| ENV-08 | architecture | PASS | loongarch64 |
| ENV-09 | java-version | PASS | openjdk version "17.0.18" 2026-01-20 |
| GIT-01 | repo-present | PASS | /home/vmuser/softbei-main-latest/softbei-main |
| GIT-02 | pull-latest | SKIP | PULL_LATEST=0 |
| GIT-05 | repo-commit | PASS | unknown |
| BUILD-01 | mvn-test | PASS | ok; log=/home/vmuser/softbei-main-latest/softbei-main/acceptance-runs/loongarch/report-20260707-131327/mvn-test.log |
| BUILD-02 | mvn-package | PASS | ok; log=/home/vmuser/softbei-main-latest/softbei-main/acceptance-runs/loongarch/report-20260707-131327/mvn-package.log |
| APP-02 | port-free | PASS | port 18080 |
| APP-03 | app-start-command | PASS | pid=1062294; log=/home/vmuser/softbei-main-latest/softbei-main/acceptance-runs/loongarch/report-20260707-131327/ops-agent.log |
| APP-04 | app-ready | PASS | health HTTP 200 |
| SEC-01 | token-required | PASS | api runtime without token returned 401 |
| OBS-01 | actuator-health | PASS | jq:.status == "UP" |
| OBS-info | actuator:/actuator/info | PASS | HTTP 200 |
| OBS-metrics | actuator:/actuator/metrics | PASS | HTTP 200 |
| OBS-prometheus | actuator:/actuator/prometheus | PASS | HTTP 200 |
| UI-01 | static-index | PASS | contains OpsGuard |
| UI-02 | token-input | PASS | contains id="apiToken" |
| UI-03 | frontend-apiFetch | PASS | contains apiFetch |
| UI-04 | frontend-token-header | PASS | contains X-Ops-Token |
| UI-05 | frontend-sse-fetch | PASS | contains text/event-stream |
| API-01 | runtime | PASS | jq:.data.llmMode == "MOCK" and .data.dryRun == true and .data.apiTokenRequired == true |
| API-02 | tools-list | PASS | jq:(.data \| length) >= 6 |
| API-03 | tools-health-inspect | PASS | jq:any(.data[]; .name == "health_inspect") |
| CHAT-01 | readonly-executed | PASS | jq:.data.status == "EXECUTED" |
| CHAT-02 | execution-plan-present | PASS | jq:(.data.executionPlan.tasks \| length) >= 1 |
| CHAT-03 | security-score-present | PASS | jq:.data.securityScore.score >= 0 |
| CHAT-04 | trace-id | PASS | 46cca1eb-ce9c-411e-8042-a10f015f895a |
| GUARD-01 | review-pending | PASS | jq:.data.status == "REVIEW_PENDING" |
| GUARD-02 | confirm-executed | PASS | jq:.data.status == "EXECUTED" |
| GUARD-03 | confirm-dry-run | PASS | jq:any(.data.execResults[]; .dryRun == true) |
| GUARD-04 | rollback-plan-present | PASS | jq:(.data.rollbackPlan \| length) >= 1 |
| GUARD-05 | forged-confirm-blocked | PASS | jq:.data.status == "REVIEW_PENDING" |
| GUARD-06 | danger-blocked | PASS | jq:.data.status == "BLOCKED" |
| GUARD-07 | injection-blocked | PASS | jq:.data.status == "INJECTION_BLOCKED" |
| GUARD-08 | dd-critical-blocked | PASS | jq:.data.status == "BLOCKED" |
| GUARD-09 | kill-pid1-blocked | PASS | jq:.data.status == "BLOCKED" |
| ROLL-01 | rollback-endpoint | PASS | jq:.data.traceId != null and (.data.results \| type == "array") |
| TRACE-01 | trace-detail | PASS | jq:.data.traceId != null and (.data.steps \| length) >= 1 |
| TRACE-02 | trace-list | PASS | jq:(.data \| length) >= 1 |
| REDact-01 | response-secret-redaction | PASS | secret not present in API response |
| INSPECT-01 | active-inspect | PASS | jq:.data.healthScore >= 0 and (.data.overall \| type == "string") |
| INSPECT-02 | inspect-findings | PASS | jq:(.data.findings \| length) >= 1 |
| RCA-01 | cross-source-rca | PASS | jq:.data.report.healthScore >= 0 and (.data.rca.overallLevel \| test("^L[123]$")) |
| RCA-02 | rca-insights | PASS | jq:(.data.rca.insights \| type == "array") |
| SSE-01 | sse-output-present | PASS | contains data: |
| SSE-02 | sse-status-present | PASS | contains status |
| SSE-03 | sse-trace-present | PASS | contains traceId |
| SSE-04 | sse-security-score | PASS | contains securityScore |
| MCPH-01 | mcp-token-required | PASS | HTTP 401 without token |
| MCPH-02 | mcp-initialize | PASS | jq:.result.protocolVersion == "2025-03-26" |
| MCPH-03 | mcp-ping | PASS | jq:.result == {} |
| MCPH-04 | mcp-tools-list | PASS | jq:(.result.tools \| length) >= 9 |
| MCPH-05 | mcp-tool-annotations | PASS | jq:all(.result.tools[] \| select([.name] \| inside(["log_rotate","service_restart","config_backup"]) \| not); .annotations.readOnlyHint == true and .annotations.destructiveHint == false) |
| MCPH-05b | mcp-mutating-annotations | PASS | jq:([.result.tools[] \| select(.annotations.readOnlyHint == false) \| .name] \| sort) == ["config_backup","log_rotate","service_restart"] |
| MCPH-06 | mcp-tools-call | PASS | jq:.result.isError == false and .result.structuredContent != null |
| MCPH-07 | mcp-invalid-tool-error | PASS | jq:.error.code == -32602 |
| MCPH-08 | mcp-mutation-review-pending | PASS | jq:.result.isError == false and .result.structuredContent.status == "REVIEW_PENDING" and .result.structuredContent.executed == false and (.result.structuredContent.pendingMutationId \| type == "string" and length > 0) |
| MCPH-09 | mcp-mutation-direct-confirm-blocked | PASS | jq:.result.structuredContent.status == "REVIEW_PENDING" and .result.structuredContent.executed == false and .result.structuredContent.traceId == null |
| MCPH-10 | mcp-mutation-confirmed | PASS | jq:.result.structuredContent.status == "EXECUTED" and .result.structuredContent.traceId != null |
| MCPS-01 | mcp-stdio | PASS | initialize/tools/list/tools/call/ping ok |
| AUDIT-01 | trace-file-created | PASS | /home/vmuser/softbei-main-latest/softbei-main/acceptance-runs/loongarch/report-20260707-131327/ops-trace.jsonl |
| AUDIT-02 | exec-output-files | PASS | 376 files |
| AUDIT-03 | audit-secret-redaction | PASS | vm-secret-token not found in trace/output audit |
| AUDIT-04 | private-key-redaction-regression | PASS | known secret markers absent |
| DEPLOY-01 | docker-compose-env-names | PASS | underscore env names |
| DEPLOY-02 | sudoers-no-restart-wildcard | PASS | no restart wildcard |
| DEPLOY-03 | sudoers-syntax | PASS | visudo -c ok |

## Artifacts

- App log: /home/vmuser/softbei-main-latest/softbei-main/acceptance-runs/loongarch/report-20260707-131327/ops-agent.log
- Trace file: /home/vmuser/softbei-main-latest/softbei-main/acceptance-runs/loongarch/report-20260707-131327/ops-trace.jsonl
- Exec output dir: /home/vmuser/softbei-main-latest/softbei-main/acceptance-runs/loongarch/report-20260707-131327/exec-output
- MCP stdio output: /home/vmuser/softbei-main-latest/softbei-main/acceptance-runs/loongarch/report-20260707-131327/mcp-stdio.out
- SSE output: /home/vmuser/softbei-main-latest/softbei-main/acceptance-runs/loongarch/report-20260707-131327/sse.out
