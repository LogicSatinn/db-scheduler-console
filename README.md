# db-scheduler-console

A dashboard for [db-scheduler](https://github.com/kagkarlsson/db-scheduler), inspired by
JobRunr's UI. Server-rendered with [JTE](https://jte.gg) + [htmx](https://htmx.org) — no
React, no Node build, no CDN calls. Add one starter dependency and open
`/db-scheduler-console`.

**Views:** overview (stat tiles, 24h throughput chart, recent failures) · executions
(filter/search/sort/paginate, run-now, reschedule, delete, bulk) · execution detail
(task data, per-instance history, stack traces) · recurring task definitions ·
searchable execution history.

> **Status:** pre-release — the current version is the `0.1.0-M1` milestone. It is fully
> functional, but details may still change before `0.1.0`.

## Quickstart

Spring Boot 3:
```kotlin
implementation("io.github.logicsatinn:db-scheduler-console-spring-boot-3-starter:0.1.0-M1")
```

Spring Boot 4:
```kotlin
implementation("io.github.logicsatinn:db-scheduler-console-spring-boot-4-starter:0.1.0-M1")
```

The starter brings in db-scheduler's own Spring Boot starter transitively — don't add a
separate db-scheduler dependency. You need: Java 17+, a servlet web app
(`spring-boot-starter-web`), a configured `DataSource`, and db-scheduler's
[`scheduled_tasks` table](https://github.com/kagkarlsson/db-scheduler#getting-started).

Start your app and open `http://localhost:8080/db-scheduler-console`.

### Execution history (recommended)

db-scheduler deletes finished executions, so the console records its own history into
`dsc_execution_history`. Create the table with the migration for your database —
the scripts ship in the jar under `db-scheduler-console/migrations/`, and the
History page shows the right script for your database until the table exists.
Retention is enforced by a `console-history-purge` recurring task (default 14 days).

## Configuration

| Property | Default | Description |
|---|---|---|
| `db-scheduler-console.enabled` | `true` | Master switch |
| `db-scheduler-console.base-path` | `/db-scheduler-console` | URL prefix |
| `db-scheduler-console.read-only` | `false` | Hide and block (403) all mutating actions |
| `db-scheduler-console.polling-interval` | `5s` | UI refresh cadence |
| `db-scheduler-console.history.enabled` | `true` | Record execution history |
| `db-scheduler-console.history.retention` | `14d` | Purge history older than this |
| `db-scheduler-console.task-data.visible` | `true` | Show task payloads in the UI |

The console reads `db-scheduler.table-name` and works with all databases db-scheduler
supports: PostgreSQL, MySQL, MariaDB, SQL Server, Oracle, H2.

## Securing the console

The console ships **no authentication** — protect it like any other actuator-style
endpoint. All routes live under one base path; GET = view, POST = act:

```java
@Bean
SecurityFilterChain dbSchedulerConsoleSecurity(HttpSecurity http) throws Exception {
    http.securityMatcher("/db-scheduler-console/**")
        .authorizeHttpRequests(auth -> auth
            .requestMatchers(HttpMethod.POST, "/db-scheduler-console/**")
                .hasRole("SCHEDULER_ADMIN")
            .anyRequest().hasAnyRole("SCHEDULER_ADMIN", "SCHEDULER_VIEWER"))
        .httpBasic(Customizer.withDefaults());
    return http.build();
}
```

CSRF: if Spring Security CSRF protection is enabled, the console sends the token
automatically on every action (htmx `hx-headers`). Don't disable CSRF for it.

## Development

```bash
./gradlew build                              # compile + fast tests (H2)
./gradlew :console-core:containerTest        # dialect matrix (needs Docker)
./gradlew :examples:boot3-example:run        # demo app on :8080
```
