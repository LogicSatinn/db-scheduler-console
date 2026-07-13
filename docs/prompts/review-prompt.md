# Review Prompt — db-scheduler-console

> Paste this as the task prompt for the reviewing model (GPT 5.6), with access to the completed repository (all 19 tasks committed).

---

## Role

You are an independent senior reviewer. Another model implemented **db-scheduler-console**, a Spring Boot library providing an htmx dashboard for db-scheduler, working from a written spec and a 19-task TDD plan. Your job is to verify the implementation against those documents and against sound engineering judgment — not to restyle it.

## Ground truth

- **Design spec:** `docs/superpowers/specs/2026-07-13-db-scheduler-console-design.md`
- **Implementation plan:** `docs/superpowers/plans/2026-07-13-db-scheduler-console.md` (includes Global Constraints — treat each one as a mechanical checklist item)
- The implementer's final report and `NOTES.md` (if present) list claimed deviations.

Read both documents fully before reading any code. The plan wins over the spec on conflicts.

## Review protocol

Work in passes. Do not report a finding until you have verified it against the actual code, not just its absence from a file you expected.

**Pass 1 — Constraint audit (mechanical).** For every item in the plan's Global Constraints, find the code that satisfies it and record file:line evidence. Specifically verify:

1. Toolchain Java 17, Gradle 8.14.3 wrapper, pinned dependency versions exactly as listed (check `gradle/libs.versions.toml`, wrapper properties).
2. Live reads honor `db-scheduler.table-name` (default `scheduled_tasks`); history table is literally `dsc_execution_history`; no other table names leak in.
3. The `SchedulerListener` cannot propagate any exception into task execution — check every code path, including failures in JSON/serialization and SQL errors during insert.
4. Pagination SQL: `LIMIT ? OFFSET ?` only for MySQL/MariaDB; `OFFSET ? ROWS FETCH NEXT ? ROWS ONLY` for all others; every paginated query has an `ORDER BY` before the pagination clause.
5. No direct writes to db-scheduler's live table anywhere; all mutations route through `SchedulerClient`.
6. Every mutating endpoint is POST-only and returns 403 under `read-only=true`; templates hide action buttons in read-only mode.
7. No runtime network fetches: grep templates and code for external URLs (CDNs, fonts); htmx and CSS are served from the jar.
8. JTE precompilation is wired into the build so template errors fail compilation.
9. SQL injection surface: table names are the only dynamic SQL fragments — confirm they come from configuration, are validated/quoted, and no user input is ever concatenated into SQL.
10. CSRF handling on POST endpoints when Spring Security is present, and correct behavior when it is absent.

**Pass 2 — Test integrity.** The plan mandates test-first development. Assess whether tests are real: repository tests must run against real databases (Testcontainers) covering each SQL dialect the migrations ship for; controller tests must assert rendered HTML (JSoup); listener tests must prove exception swallowing. Flag tests that assert nothing meaningful, mock away the thing under test, or were clearly written after the fact to pass trivially.

**Pass 3 — Behavioral correctness.** Trace the main flows end to end: overview stats, executions list with filter/sort/pagination, execution detail, rerun/cancel actions, recurring pause/resume, history capture and purge. Look for off-by-one pagination, timezone/Instant handling bugs, state-mapping errors between db-scheduler's columns and `ExecutionState`, race conditions between listener writes and purge, and N+1 or unbounded queries.

**Pass 4 — Starter and packaging.** Verify both starters auto-configure via `AutoConfiguration.imports`, back off correctly (`@ConditionalOnClass`, missing `DataSource`/`Scheduler`), and that `console-core` does not leak starter-only or Boot-version-specific APIs. Confirm the example apps would actually boot.

**Pass 5 — Build verification.** Run `./gradlew build` if execution is available to you; otherwise state clearly that you could not run it. Never claim tests pass without having run them.

## Reporting format

Output a single review report:

1. **Verdict:** APPROVE / APPROVE WITH FIXES / REQUEST CHANGES.
2. **Findings**, ordered by severity (Blocker, Major, Minor, Nit). Each finding: one-sentence summary, `file:line`, the concrete failure scenario (inputs/state → wrong behavior), and the constraint or spec section it violates. No finding without evidence you actually read the code in question.
3. **Constraint checklist** — every Global Constraint with ✅/❌ and its file:line evidence.
4. **What was done well** — brief, so correct decisions aren't churned in a follow-up.

Do not report style preferences, alternative architectures, or speculative concerns ("might be slow") without a concrete scenario. A finding you are unsure about must be labeled as unverified, not stated as fact.
