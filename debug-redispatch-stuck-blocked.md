[OPEN] Debug Session: redispatch-stuck-blocked

## Summary
- Symptom: `verify-subtask-redispatch-auto-execution.ps1 -Scenario blocked -BindVault` reassign succeeds, subTask transitions `ASSIGNED -> BLOCKED` and never reaches `REVIEW` within timeout.
- Expected: After reassign to `API_KEY_LLM`, auto execution should run and submit subTask to `REVIEW`.
- Environment: Windows, local backend restarted by user.

## Reproduction
1. Ensure backend is running.
2. Ensure `DEEPSEEK_API_KEY` is set if running real+require-vault.
3. Run:
   - `.\verify-subtask-redispatch-auto-execution.ps1 -Scenario blocked -BindVault`

## Hypotheses (falsifiable)
- H1: Auto execution throws inside `SubTaskExecutionService.executeOnce` (or deeper) and catch-path blocks the subTask (`IN_PROGRESS -> BLOCKED`).
- H2: `API_KEY_LLM` execution fails due to missing/invalid vault credential/provider mismatch, causing executor to throw immediately.
- H3: `PlatformAgentExecutionService.executeSync(...).join()` wraps an underlying exception (CompletionException) and the root cause is hidden in normal logs/HTTP response.
- H4: Reassign dispatch triggers auto execution twice (duplicate event) causing an illegal state transition and ends up BLOCKED.
- H5: Dispatch/auto-execution runs but `subTaskService.submit()` fails (e.g., state not `IN_PROGRESS`), leaving it stuck; subsequent compensation moves it to BLOCKED.

## Evidence Plan
- Start TRAE Debug Server and collect structured runtime events from:
  - `SubTaskAutoExecutionDispatcher.onAssigned`
  - `SubTaskExecutionService.executeOnce`
  - `PlatformAgentExecutionService.executeSync`
- Compare pre/post instrumentation runs by `runId`.

## Status
- Next step: add instrumentation and rerun the script.

