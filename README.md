# db-scheduler-console

A dashboard for [db-scheduler](https://github.com/kagkarlsson/db-scheduler), inspired by
JobRunr's UI. Server-rendered with [JTE](https://jte.gg) + [htmx](https://htmx.org) — no
React, no Node build, no CDN calls. Add one starter dependency and open
`/db-scheduler-console`.

**Views:** overview (stat tiles, 24h throughput chart, recent failures) · executions
(filter/search/sort/paginate, run-now, reschedule, delete, bulk) · execution detail
(task data, per-instance history, stack traces) · recurring task definitions ·
searchable payload-aware execution history · retrying and parked-failure states · transactional
requeue/delete actions.

> **Status:** pre-release — the current version is the `0.1.0-M2` milestone. It is fully
> functional, but details may still change before `0.1.0`.

## Quickstart

Spring Boot 3:
```kotlin
implementation("io.github.logicsatinn:db-scheduler-console-spring-boot-3-starter:0.1.0-M2")
```

Spring Boot 4:
```kotlin
implementation("io.github.logicsatinn:db-scheduler-console-spring-boot-4-starter:0.1.0-M2")
```

The starter brings in db-scheduler's own Spring Boot starter transitively — don't add a
separate db-scheduler dependency. You need: Java 17+, a servlet web app
(`spring-boot-starter-web`), a configured `DataSource`, and db-scheduler's
[`scheduled_tasks` table](https://github.com/kagkarlsson/db-scheduler#getting-started).

The starter activates only when the host already provides a servlet `Scheduler` and `DataSource`.
It does not apply migrations, install authentication, select a retry policy, or configure a
client-only scheduler. Start your app and open `http://localhost:8080/db-scheduler-console`.

### Execution history (recommended)

db-scheduler deletes finished executions, so the console records its own history into
`dsc_execution_history`. Fresh-install scripts for every supported database ship under
`db-scheduler-console/migrations/`. Existing `0.1.0-M1` installations must apply the matching
script under `db-scheduler-console/migrations/upgrade/0.1.0-M1-to-0.1.0-M2/`. The History page
detects missing, M1 (legacy), and M2 schemas and shows the appropriate guidance. Legacy schemas
continue metadata-only writes until upgraded.

M2 retains the serialized task payload and its Java type for every successful and failed attempt.
Storage is controlled independently from UI visibility. Payloads can contain personal data,
credentials, or financial identifiers, so review task DTOs and database access before enabling
this in production. History is purged by completion time after 14 days by default; parked failures
are never purged automatically.

### Parking exhausted one-time executions

Retry and parking policy remains host-owned. Create `FailedExecutionParking` before defining the
tasks that use it, then compose it with db-scheduler's retry builder:

```java
@Bean
FailedExecutionParking failedExecutionParking(DataSource dataSource) {
    return new FailedExecutionParking(dataSource); // pass the custom scheduled_tasks name if used
}

@Bean
OneTimeTask<PaymentPayloadV1> paymentTask(FailedExecutionParking parking,
        PaymentHandler handler) {
    return Tasks.oneTime("payment-v1", PaymentPayloadV1.class)
        .onFailure(FailureHandler.<PaymentPayloadV1>maxRetries(3)
            .withBackoff(Duration.ofSeconds(3), 2) // retries after 3, 6, and 12 seconds
            .then(parking.failureHandler()))
        .execute((instance, context) -> handler.process(instance.getData()));
}
```

After the third retry is exhausted, the terminal attempt atomically moves the picked row from
`scheduled_tasks` to `dsc_failed_execution`. A parking failure rolls back and leaves the live row
authoritative. The console resolves the current registered task and validates deserialization
before atomically requeueing the same instance, payload, and priority.

## Configuration

| Property | Default | Description |
|---|---|---|
| `db-scheduler-console.enabled` | `true` | Master switch |
| `db-scheduler-console.base-path` | `/db-scheduler-console` | URL prefix |
| `db-scheduler-console.read-only` | `false` | Hide and block (403) all mutating actions |
| `db-scheduler-console.polling-interval` | `5s` | UI refresh cadence |
| `db-scheduler-console.history.enabled` | `true` | Record execution history |
| `db-scheduler-console.history.store-task-data` | `true` | Retain serialized payloads in history |
| `db-scheduler-console.history.retention` | `14d` | Purge history older than this |
| `db-scheduler-console.task-data.visible` | `true` | Show task payloads in the UI |

The console reads `db-scheduler.table-name` and works with all databases db-scheduler
supports: PostgreSQL, MySQL, MariaDB, SQL Server, Oracle, H2.

## Securing the console

The console ships **no authentication** — protect it like any other actuator-style endpoint.
All routes live under one base path; GET = view, POST = act. Keep CSRF enabled for mutations:

```java
@Bean
@Order(1)
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

The Boot 3 and Boot 4 examples include host-owned Basic Auth chains. When Spring Security CSRF
protection is enabled, the console sends the token automatically on every htmx action through
`hx-headers`; unauthenticated requests and tokenless mutations are rejected.

## Development

```bash
./gradlew build                              # compile + fast tests (H2)
./gradlew :console-core:containerTest        # dialect matrix (needs Docker)
./gradlew :examples:boot3-example:run        # demo app on :8080
```
