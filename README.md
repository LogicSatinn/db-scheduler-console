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

History payload retention is opt-in: set `db-scheduler-console.history.store-task-data=true`
to retain the serialized task payload and its Java type for each attempt.
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
| `db-scheduler-console.read-only` | `true` | Hide and block (403) all mutating actions |
| `db-scheduler-console.allow-framing` | `false` | Permit host-controlled embedding; does not weaken host headers |
| `db-scheduler-console.external-origin` | unset | Expected external origin for mutation Origin checks |
| `db-scheduler-console.polling-interval` | `5s` | UI refresh cadence |
| `db-scheduler-console.history.enabled` | `true` | Record execution history |
| `db-scheduler-console.history.store-task-data` | `false` | Retain serialized payloads in history |
| `db-scheduler-console.history.retention` | `14d` | Purge history older than this |
| `db-scheduler-console.task-data.visible` | `false` | Show task payloads in the UI |

The console reads `db-scheduler.table-name` and works with all databases db-scheduler
supports: PostgreSQL, MySQL, MariaDB, SQL Server, Oracle, H2.

## Pagination

Executions and history use cursor/keyset pagination with Previous/Next links, not numbered pages
or offsets. Cursors are versioned, validated and bound to filters, size and sorting. Changing those
inputs starts a new traversal. Each query returns at most `size + 1` rows (size is 1–200), excludes
payload BLOBs, and does not count all matching rows. The combined execution query orders live and
parked metadata together in the database. Active executions can move between pages as their sort
keys change; this is not a snapshot. Refresh from the first page to see the latest state.

Fresh-install migrations include history cursor indexes. Existing M2 installations can apply the
matching script in `db-scheduler-console/migrations/upgrade/0.1.0-M2-security/`. The scheduler table
is host-owned: review an index on `(execution_time, task_name, task_instance)` for the default sort,
and composite indexes matching other frequently used sorts/filters. Adapt the table name to
`db-scheduler.table-name`. Check query plans on your database; substring search, nullable heartbeat
sorting and combined-table sorting can still require scans/sorts even though application memory
and returned row counts are bounded.

## Securing the console

The console ships **no authentication** — protect it like any other actuator-style endpoint.
All routes live under one base path; GET = view, POST = act. Keep CSRF enabled for mutations:

```java
@Bean
@Order(1)
SecurityFilterChain dbSchedulerConsoleSecurity(HttpSecurity http, ConsoleProperties props)
        throws Exception {
    String base = props.getBasePath();
    http.securityMatcher(base, base + "/**")
        .authorizeHttpRequests(auth -> auth
            // Optional: give payload viewers a stricter policy than metadata viewers.
            .requestMatchers(base + "/payload/**").hasRole("SCHEDULER_ADMIN")
            .requestMatchers(HttpMethod.POST, base, base + "/**").hasRole("SCHEDULER_ADMIN")
            .anyRequest().hasAnyRole("SCHEDULER_ADMIN", "SCHEDULER_VIEWER"))
        .httpBasic(Customizer.withDefaults());
    return http.build();
}
```

Protect the root **and** its descendants with the same configured base path. This chain does not
protect other application routes: retain the host's normal security chains. The standalone
examples additionally install a last, deny-all chain; the reusable starters deliberately do not.

### Browser mutations and network boundaries

Host CSRF enforcement is mandatory for browser-accessible mutations, including HTTP Basic Auth,
reverse-proxy SSO and container authentication. Basic credentials can be sent automatically by a
browser; Basic Auth is not a reason to disable CSRF. Spring Security tokens are sent through
`hx-headers`. A host using another authentication layer must also supply CSRF enforcement and
integrate its token with the UI. Keep the console read-only until that integration is complete.

The console additionally requires `HX-Request: true` on POSTs, rejects `Sec-Fetch-Site: cross-site`
and rejects a supplied Origin that differs from the expected origin. Direct clients must send the
custom header **and** satisfy the host's authentication/CSRF requirements. Missing Origin/Fetch
Metadata headers are allowed for direct clients; the custom header is not authentication or a
replacement for CSRF tokens. Permissive credentialed CORS can defeat custom-header protection.

Behind a proxy, configure `db-scheduler-console.external-origin=https://console.example.com` or
configure trusted forwarded-header handling in the host. Do not trust forwarded headers from
untrusted clients. Require HTTPS outside local development, restrict network access to operators,
and grant the application database account only the required table permissions.

### Payloads, requeue and diagnostics

Visibility and retention are independent and both default to false. `task-data.visible=true`
allows lazy preview fetches on expansion; it does not enable storage. `history.store-task-data=true`
enables storage; it does not grant UI access. Existing records are not removed when either setting
is turned off. Parked payloads are needed for requeue and are retained independently.

The default preview never deserializes objects: it shows printable UTF-8 or binary metadata, reads
at most 64 KiB plus one sentinel byte, and displays at most 10,000 characters. An explicit
`TrustedTaskDataFormatter` bean can provide richer **text** output for bounded input. That formatter
is trusted host code: it must not deserialize untrusted objects without a reviewed policy. Its
output is inserted as text, not HTML. Both payload routes remain forbidden when visibility is off.

Requeue still validates compatibility with the currently registered task before re-inserting the
original bytes. A class cast occurs **after** Java deserialization and provides no protection
against deserialization callbacks. The default Java serializer is refused for requeue unless a
host JVM filter is present. For untrusted stored bytes, configure a reviewed filtering serializer
through the scheduler's `DbSchedulerCustomizer.serializer()` or a host-owned `jdk.serialFilter`.
A custom serializer is an explicit host trust boundary, not proof that its implementation is safe.

`FilteringJavaSerializer` is available for a per-stream policy. Supply an `ObjectInputFilter` with
an explicit allowlist for the entire DTO graph (including required arrays and nested types), not
blanket `java.*`/collection allowances. It rejects unlisted classes and enforces depth 20,
10,000 references, array length 10,000 and input size 1 MiB. Requeue also caps input at 1 MiB.
For example, adapt and review this pattern for your DTOs:

```java
new FilteringJavaSerializer(ObjectInputFilter.Config.createFilter(
    "com.example.PaymentPayloadV1;com.example.PaymentLineV1;!*"));
```

Hosts retaining the default serializer can instead configure `-Djdk.serialFilter=...` at JVM startup.
Include `maxdepth`, `maxrefs`, `maxarray`, `maxbytes`, reviewed class rules and a final `!*` rejection.
The console never installs or changes JVM-wide filter policy. JVM filters also affect other Java
serialization in the application; test legitimate payload graphs before deploying.

Payload settings do **not** hide exception messages or stack traces. Supply a `DiagnosticSanitizer`
bean to redact those strings before history/parking writes and when existing records are read.
The default preserves diagnostics; a failing sanitizer redacts the diagnostic. Sanitizing reads
does not erase existing database content or backups. Unexpected action/request failures show stable
messages with correlation IDs; console error logs record IDs and exception types, not raw messages
or payloads. Host/scheduler logging needs its own sensitive-data policy.

Disabling `history.enabled` disables both recording **and the purge task**. Define an explicit
retention/deletion plan for existing history and backups. Parked failures are never automatically
purged. Retention and diagnostic sanitization remain necessary even when payloads are hidden.

### Caching, framing and CSP

Sensitive pages, fragments and actions use `Cache-Control: no-store` and `nosniff`. Static assets
remain cacheable. Framing is blocked by default; approved embedding requires
`db-scheduler-console.allow-framing=true` and a host CSP `frame-ancestors` allowlist. The console
preserves existing host headers; opting into embedding does not remove a host's framing restriction.

The UI supports this policy (install it through the host security configuration):

```text
default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; base-uri 'none'; object-src 'none'; form-action 'self'; frame-ancestors 'none'
```

Handlers and styles are external. The console uses htmx 4 with evaluation (`hx-on`, `js:` values
and trigger filters), script execution from swapped responses and injected indicator styles
disabled. This is a specific supported policy, not a promise of compatibility with every CSP. Host
logout should invalidate sessions; HTTP Basic Auth has browser credential-cache semantics and is
not a session logout mechanism.

### Existing htmx snapshots and logout

htmx 4 keeps no history snapshots, and the layout sets `"history": "reload"` so back/forward
navigation always requests a fresh page. Older console releases ran htmx 2, which stored snapshots in
the `htmx-history-cache` localStorage array; the console still removes only its own entries from
that array on startup and page exit. It clears sensitive DOM before browser back/forward caching
and reloads restored pages for fresh authorization. Other host storage and unrelated htmx snapshots
are preserved.

For rollout and host logout, load the new `static/console.js` with `defer` from a host-authorized
location, then call `window.dbSchedulerConsole.clearHistory(["/db-scheduler-console", "/previous-console-path"])`
from external JavaScript. Include any servlet context prefix. On a non-console page the script only
exposes this cleanup API. Deploy this cleanup to host pages that could restore old snapshots too:
new console code cannot retroactively execute inside an old snapshot without a fresh load. Clear
known old base paths before restoring host htmx history, and invalidate authorization server-side.
Do not clear all localStorage or unrelated host snapshots.

### Standalone examples

Examples bind to `127.0.0.1` and require `CONSOLE_USERNAME` and `CONSOLE_PASSWORD`; no demo password
is shipped. Supply these through your environment/secret manager before running an example. A
nonlocal bind rejects missing credentials and the historical demo password. Configure TLS at the
application or trusted proxy before exposing Basic Auth. Set `db-scheduler-console.read-only=false`
only after configuring authentication and CSRF. Do not deploy examples as production infrastructure.

See [release notes](SECURITY-RELEASE-NOTES.md) for migration and verification requirements.

## Development

```bash
./gradlew build                              # compile + fast tests (H2)
./gradlew :console-core:containerTest        # dialect matrix (needs Docker)
./gradlew :examples:boot3-example:run        # demo app on :8080
```
