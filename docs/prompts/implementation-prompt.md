# Implementation Prompt — db-scheduler-console

> Paste this as the task prompt for the implementing model (Claude Opus or Sonnet), run from the repo root of `db-scheduler-console`.

---

## Goal

Implement **db-scheduler-console** end to end: a Spring Boot library (with Boot 3 and Boot 4 starters) that auto-configures an htmx server-rendered dashboard for [db-scheduler](https://github.com/kagkarlsson/db-scheduler), providing live execution views, execution history, stats with charts, and admin actions (rerun, cancel, pause/resume recurring tasks).

You are done when a user can add one starter dependency to a Boot 3 or Boot 4 app and get a working, secured, read-only-capable dashboard at `/db-scheduler-console` with zero extra configuration.

## Authoritative documents (read both fully before writing any code)

- **Design spec:** `docs/superpowers/specs/2026-07-13-db-scheduler-console-design.md` — the product and architecture decisions. When in doubt about behavior, this wins.
- **Implementation plan:** `docs/superpowers/plans/2026-07-13-db-scheduler-console.md` — 19 ordered TDD tasks with file lists, interfaces, and commit messages. Execute the tasks **in order**; each task's checkbox steps are your working checklist.

Do not redesign. Where the plan gives exact code, versions, table names, SQL, or CSS tokens, use them verbatim. If you find a genuine contradiction between spec and plan, prefer the plan (it is newer) and record the discrepancy in a `NOTES.md` at the repo root rather than silently choosing.

## Hard constraints (violations are review failures)

1. Java toolchain **17**, Gradle wrapper **8.14.3**, Kotlin DSL, version catalog. Pinned versions exactly as listed in the plan's Global Constraints.
2. db-scheduler's live table is **`scheduled_tasks`** by default and the configured `db-scheduler.table-name` must always be honored. The history table is fixed: **`dsc_execution_history`**.
3. The `SchedulerListener` that captures history **must never throw** into task execution — catch-all with a WARN log.
4. All SQL is ANSI except pagination: MySQL/MariaDB use `LIMIT ? OFFSET ?`; every other dialect uses `OFFSET ? ROWS FETCH NEXT ? ROWS ONLY`, always after an `ORDER BY`.
5. Reads of live state go through dialect-aware JDBC only; **all mutations go through `SchedulerClient`** — never write to db-scheduler's table directly.
6. Mutating endpoints are POST-only; `db-scheduler-console.read-only=true` returns 403 on them and hides the buttons in templates.
7. No runtime network calls: htmx 2.0.10 and CSS ship inside the jar and are served by `StaticAssetsController`.
8. JTE templates are **precompiled** at build time; a template compile error must fail the build, not the first request.
9. **TDD is mandatory**: for every code task, write the failing test first, watch it fail, then implement. Repository tests run against real databases via Testcontainers; controller tests assert rendered HTML with JSoup; async assertions use Awaitility.
10. Commit at the end of every task using exactly the commit message the plan specifies for that task, including the `Co-Authored-By` trailer the plan mandates.

## Working method

- Work task-by-task: Task 1 through Task 19, no skipping, no batching commits.
- After each task: run the full build (`./gradlew build`) and only commit when it is green. Never commit a red build.
- If a pinned dependency version is unresolvable from Maven Central, stop and report it rather than substituting a different version.
- Keep code plain and idiomatic Java 17 — records and sealed types where the plan calls for them, no Lombok, no reflection tricks, no dependencies beyond the version catalog.
- The examples (`examples/boot3-example`, `examples/boot4-example`) must actually start and serve the console; verify with their tests per Task 18.

## Deliverable / final report

When all 19 tasks are committed, produce a summary containing:

1. The output of `./gradlew build` (final run, all modules).
2. A task-by-task list mapping each task to its commit hash.
3. Any deviations from the plan, with justification (an empty list is the expected outcome).
4. Anything you left as a known gap, explicitly flagged.

This work will be independently reviewed against the spec and plan by another model. Assume every constraint above will be checked mechanically.
