# db-scheduler-console — Design

**Date:** 2026-07-13
**Status:** Approved
**Author:** Calvin (with Claude)

## Context & Motivation

[db-scheduler](https://github.com/kagkarlsson/db-scheduler) is a persistent, cluster-friendly task scheduler for Java, but it ships no UI. The existing open-source dashboard, [bekk/db-scheduler-ui](https://github.com/bekk/db-scheduler-ui), is a React SPA bundled into a Spring Boot starter. It works, but its UX is a task table with actions — far from the information-dense, live-feeling dashboard [JobRunr](https://www.jobrunr.io/en/) ships.

db-scheduler-console is a Spring Boot library that provides a JobRunr-grade dashboard for db-scheduler, built with server-rendered templates and htmx — no React, no Node build chain, no CDN calls. It is an **internal tool first**: built for our own Spring Boot apps, designed cleanly enough to open-source later if it proves itself.

## Goals

- Drop-in Spring Boot starter (Boot 3.x and Boot 4.x): add one dependency, get a dashboard at `/db-scheduler-console`.
- JobRunr-grade UX: overview with stat tiles and throughput chart, filterable execution lists, execution detail, recurring-task view, searchable execution history.
- Full admin actions: run now, re-run failed, reschedule, delete — plus bulk variants.
- Execution history recorded by the library itself (db-scheduler does not retain completed executions).
- All db-scheduler-supported databases: PostgreSQL, MySQL, MariaDB, SQL Server, Oracle, H2.
- Security delegated entirely to the host application ("userland"); the library ships safe defaults and documentation, not auth.
- Self-contained assets: htmx and CSS vendored into the jar; works air-gapped.

## Non-Goals (v1)

- Non-Spring integrations (Ktor, Javalin, plain servlet). The internal package boundaries keep this possible later, but no adapter is built now.
- Publishing to Maven Central (internal-first; revisit after it proves itself).
- Built-in authentication or user management.
- Real-time push (SSE/WebSocket) — htmx polling is sufficient for v1.
- JavaScript chart libraries — charts are server-rendered SVG.

## Decisions Log

| Decision | Choice |
|---|---|
| Ambition | Internal tool first, JobRunr-grade UX, htmx-based |
| Scope | Full admin actions (not monitoring-only) |
| Security | Delegated to host app; method-based read/write split; `read-only` mode |
| Language | Java |
| Baseline | Java 17, Spring Boot 3.x and 4.x starters |
| History | In scope for v1, via library-owned table + listener |
| Databases | All db-scheduler-supported |
| Architecture | Shared core + thin per-Boot-generation starters |
| Build tool | Gradle (Kotlin DSL) |
| Rendering | JTE precompiled templates + vendored htmx |
| Name | db-scheduler-console |

## Architecture

Gradle (Kotlin DSL) multi-module project:

```
db-scheduler-console/
├── console-core/                      # ~95% of the code
│   ├── web/        Spring MVC controllers + htmx fragment endpoints
│   ├── service/    stats, execution queries, actions, history, purge
│   ├── data/       JDBC repositories, Dialect abstraction, migration scripts
│   ├── templates/  JTE templates, precompiled to classes at build time
│   └── static/     vendored htmx.min.js, hand-written CSS
├── console-spring-boot-3-starter/     # @AutoConfiguration for Boot 3.x only
├── console-spring-boot-4-starter/     # @AutoConfiguration for Boot 4.x only
└── examples/
    ├── boot3-example/                 # dev playground + CI smoke test
    └── boot4-example/                 # CI compatibility proof
```

- `console-core` compiles against Spring Framework 6 and db-scheduler ≥ v16. Spring MVC annotations are stable across Framework 6→7, so the same core runs under both Boot generations; CI proves it with both example apps.
- Starters contain only auto-configuration (bean wiring, `@ConditionalOnBean(Scheduler.class)`, property binding). No logic.
- JTE templates are precompiled by the JTE Gradle plugin: no runtime template engine configuration, no interference with the host app's view resolvers, only the small `jte-runtime` dependency at runtime.
- Auto-configuration activates only when a db-scheduler `Scheduler` bean exists and `db-scheduler-console.enabled=true` (default). Otherwise the library is inert.

## Data Layer

### Live state

Live execution state is read directly from db-scheduler's `scheduled_executions` table via JDBC (`JdbcTemplate` on the same `DataSource` db-scheduler uses). The `SchedulerClient` API is not used for reads — it cannot express SQL-level filtering, sorting, and pagination.

- Queries stay ANSI where possible; the few dialect-specific parts (pagination syntax) live behind a single `Dialect` interface with one implementation per supported database.
- Dialect is auto-detected from `DataSource` metadata; unknown databases disable the dashboard with a single clear ERROR log line — the host app boots normally.
- Derived states presented in the UI: **Scheduled** (future execution), **Due** (execution_time in the past, not picked), **Running** (picked), **Failing** (consecutive_failures > 0).

### Execution history

db-scheduler deletes completed executions, so the library owns a history table:

```sql
dsc_execution_history (
  id                BIGINT / IDENTITY,   -- per-dialect
  task_name         VARCHAR(...),
  task_instance     VARCHAR(...),
  outcome           VARCHAR(16),         -- SUCCEEDED | FAILED
  started_at        TIMESTAMP,
  finished_at       TIMESTAMP,
  duration_ms       BIGINT,
  exception_class   VARCHAR(...),        -- nullable
  exception_message VARCHAR(...),        -- nullable, truncated
  stacktrace        TEXT/CLOB,           -- nullable, truncated
  picked_by         VARCHAR(...)         -- scheduler instance name
)
-- indexes: (task_name, started_at), (started_at), (outcome, started_at)
```

- Populated by a listener implementing db-scheduler's `SchedulerListener` API, using `ExecutionComplete` (result, cause, timeStarted, timeDone) for outcome, duration, and stacktrace.
- Wiring: auto-registered by our starter. Exact mechanism (listener bean pickup by the db-scheduler Spring Boot starter vs. explicit registration) is verified against the pinned db-scheduler version during implementation planning; both paths are auto-configured, no user code either way.
- **The listener never throws into task execution.** Any history-write failure is caught, WARN-logged, and dropped. The insert is a single statement on the existing DataSource — cheap enough to run synchronously on execution completion.
- Migration scripts ship inside the jar as plain SQL, one per database, applied by the user via their own tool (Flyway/Liquibase/manual) — the same convention db-scheduler itself uses.
- If the history table is missing: live views work normally; history views degrade to a setup page showing the correct `CREATE TABLE` script for the detected database.

### Retention & purge

`db-scheduler-console.history.retention` (default `14d`) is enforced by a purge job the library registers **as a db-scheduler recurring task** (`console-history-purge`, daily). Dogfooding: the purge task itself appears in the dashboard.

### Stats & charts

- Stat tiles: portable `COUNT(*)` queries against live + history tables.
- Throughput chart (last 24h): fetch the window's history rows (bounded query), bucket per hour in Java. No dialect-specific date functions.

## Web Layer & UI

Five views, all JTE server-rendered, htmx for interactivity. JobRunr's dashboard is the UX reference.

1. **Overview** — stat tiles (Scheduled, Due, Running, Failing, Succeeded 24h, Failed 24h), 24h throughput chart (server-rendered SVG), 5 most recent failures. Tiles + chart poll via `hx-trigger="every Ns"` (configurable, default 5s).
2. **Executions** — filterable live table: state, task name (distinct dropdown), instance-id search, time range. Sortable columns, pagination. Row actions + bulk actions. Filters sync to URL via `hx-push-url` (shareable/bookmarkable).
3. **Execution detail** — identity, current state, next run, task data, per-instance history timeline, stacktraces, actions.
4. **Recurring tasks** — task definitions known to the app (injected `Task<?>` beans from the Spring context) joined with next execution and last outcome.
5. **History** — searchable: task, outcome, time range, free-text on exception; expandable stacktraces; pagination.

htmx patterns: `hx-boost` navigation; polling swaps only the changed fragment (table body, tile row); forms via `hx-post` returning fragments; flash-message fragment for action results.

**Task data display:** db-scheduler serializes `task_data` with a pluggable `Serializer` (default: Java serialization). If the host app's serializer produces JSON, payloads are pretty-printed; otherwise show type + byte size without pretending to decode. `db-scheduler-console.task-data.visible=false` hides payloads entirely.

## Actions

All mutations go through db-scheduler's `SchedulerClient` — never raw SQL — so optimistic-locking (`version`) semantics are respected. A stale action (e.g., "run now" on an execution just picked by another instance) fails cleanly with a flash message and a table refresh; no double-runs.

| Action | Mechanism |
|---|---|
| Run now | `SchedulerClient.reschedule(...)` to now |
| Re-run failed | Same as run now, surfaced on failing executions |
| Reschedule | `reschedule(...)` to picked datetime |
| Delete execution | `SchedulerClient.cancel(...)` |
| Bulk run / delete | Iterate selected ids; report per-row success/failure |

All actions are POST-only and server-side enforced (403 in read-only mode; buttons also hidden).

## Security Model

Security is userland's responsibility, by design:

- Every endpoint lives under one configurable base path (`/db-scheduler-console`) — a single `securityMatcher` covers the whole dashboard.
- Read/write split by HTTP method: GET = view, POST = mutate. Documentation ships a copy-paste Spring Security snippet (viewer role → GET, admin role → POST).
- `db-scheduler-console.read-only=true` disables all mutations server-side regardless of auth.
- CSRF: when Spring Security CSRF is active, the layout template emits the token in a meta tag and htmx sends it via `hx-headers`. We never suggest disabling CSRF.
- No external requests of any kind (no CDN, no fonts, no telemetry).
- The library itself performs no authentication and stores no credentials.

## Configuration Properties

| Property | Default | Purpose |
|---|---|---|
| `db-scheduler-console.enabled` | `true` | Master switch |
| `db-scheduler-console.base-path` | `/db-scheduler-console` | URL prefix |
| `db-scheduler-console.read-only` | `false` | Disable all mutations |
| `db-scheduler-console.polling-interval` | `5s` | htmx refresh cadence |
| `db-scheduler-console.history.enabled` | `true` | History capture + views |
| `db-scheduler-console.history.retention` | `14d` | Purge threshold |
| `db-scheduler-console.task-data.visible` | `true` | Show task payloads |

## Error Handling

Prime directive: **the dashboard must never hurt the host app.**

- History listener: catch-all, WARN-log, never propagate into task execution.
- Missing history table: graceful degradation + setup page with correct DDL.
- Action conflicts (optimistic lock, already-picked, deleted): flash message + fragment refresh to current reality.
- Unsupported database: dashboard self-disables with one ERROR log; app boots.
- No `Scheduler` bean or `enabled=false`: auto-configuration backs off entirely.

## Testing

- **Repository layer:** Testcontainers matrix — PostgreSQL, MySQL, MariaDB, SQL Server, Oracle Free — plus in-JVM H2. Every query and every migration script runs against all six.
- **Web layer:** MockMvc + JSoup assertions on rendered HTML (filters produce correct rows, read-only hides buttons and 403s POSTs, CSRF token present).
- **History listener:** integration test with a real embedded `Scheduler` (H2 + Postgres): run tasks, assert history rows; prove a throwing history writer does not fail task execution.
- **Boot 3/4 compatibility:** both example apps run context-load + HTTP smoke tests in CI.
- Example apps are the dev playground, seeded with demo tasks (fast recurring, slow, always-failing, one-time) so the UI has live data during development.

## Compatibility

| Dimension | Supported |
|---|---|
| Java | 17+ |
| Spring Boot | 3.x (starter), 4.x (starter) |
| db-scheduler | ≥ 16.x (pinned minimum verified during implementation) |
| Databases | PostgreSQL, MySQL, MariaDB, SQL Server, Oracle, H2 |

## Future (explicitly deferred)

- Framework-agnostic core extraction + Ktor/Javalin adapters
- Maven Central publication (via the Central Portal with the `com.vanniktech.maven.publish` Gradle plugin on `console-core` and both starters; needs a verified namespace, e.g. `io.github.logicsatinn`), semver policy, public docs site
- SSE-based live updates replacing polling
- Per-task metrics (p95 duration trends), alerting hooks
