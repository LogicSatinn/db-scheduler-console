# Implementation notes

Deviations from `docs/superpowers/plans/2026-07-13-db-scheduler-console.md`, with justification,
plus known gaps.

## Deviations

### 1. `testRuntimeOnly("org.junit.platform:junit-platform-launcher")` added to every module with tests

**Plan text:** Task 1's build files (Steps 5–8) list no `junit-platform-launcher` dependency.

**Why it was necessary:** Gradle 8.14.3 embeds an older `junit-platform-launcher` than the
JUnit Platform (1.12.x) that the Spring Boot 3.5.16 / 4.0.7 BOMs bring in via
`spring-boot-starter-test`. Without an explicit launcher on the test runtime classpath, *every*
test fails at discovery:

```
org.junit.platform.commons.JUnitException: TestEngine with ID 'junit-jupiter' failed to discover tests
Caused by: OutputDirectoryProvider not available; probably due to unaligned versions of the
junit-platform-engine and junit-platform-launcher jars on the classpath/module path.
```

Adding `testRuntimeOnly("org.junit.platform:junit-platform-launcher")` (version managed by the
Boot BOM already declared in each module) is the fix documented by both Gradle and Spring Boot.
It changes no production dependency and no published artifact.

### 2. Trailing `;` added to `console-core/src/test/resources/db-scheduler-schema/oracle.sql`

**Plan text:** Task 5, Step 1 — *"(Oracle script has no trailing semicolon-separated statements
issue: one statement, no trailing `;`.)"* — i.e. the plan deliberately omits the `;`.

**Why it was necessary:** the plan's own test helper loads these fixtures with Spring's
`ResourceDatabasePopulator`. `ScriptUtils` only splits on `;` if the script *contains* a `;`;
otherwise it falls back to `FALLBACK_STATEMENT_SEPARATOR = "\n"` and splits the script **line by
line**. Without the `;`, the Oracle DDL was sent one line at a time and the first line alone
(`create table scheduled_tasks (`) reached the server:

```
java.sql.SQLSyntaxErrorException: ORA-00931: missing identifier
```

With the trailing `;` the file is one statement (the separator is stripped before execution, so
Oracle never sees the `;`) and `OracleContractTest` passes. This affects a test fixture only — no
production SQL, and no shipped migration, changed.

### 3. `ExecutionActions.InstanceRef.parse` uses `substring(i + SEP.length())`, not `substring(i + 1)`

**Plan text:** Task 8, Step 3 — `return new InstanceRef(composite.substring(0, i), composite.substring(i + 1));`

**Why it was necessary:** the separator is `"::"` (two characters), so `i + 1` leaves a leading `:`
on the instance id and `parse("t::i")` yields `InstanceRef("t", ":i")`. That breaks the plan's own
`instanceRefRoundTrip` test, which asserts `parse(ref.composite()).equals(ref)`. Advancing by
`SEP.length()` is the minimal fix and preserves the documented "split on the first `::`" contract
(instance ids may themselves contain `::`).

### 4. `-parameters` compiler flag added in the root `build.gradle.kts` (`allprojects`)

**Plan text:** Task 1's build files set no compiler args.

**Why it was necessary:** Spring MVC resolves `@PathVariable`/`@RequestParam` names by reflection.
The Spring Boot Gradle plugin adds `-parameters` for you, but the plan deliberately does not apply
that plugin to any module (Task 1, Step 8 explains why). Without the flag,
`StaticAssetsController.asset(@PathVariable String file)` fails at request time:

```
IllegalArgumentException: Name for argument of type [java.lang.String] not specified,
and parameter name information not available via reflection.
Ensure that the compiler uses the '-parameters' flag.
```

Applied once in `allprojects` so every module behaves like a Boot-plugin build. No dependency or
API change.

### 5. `layout.jte` uses a JTE "smart attribute" for `hx-headers` instead of `@if` around the attribute

**Plan text:** Task 10, Step 8 — `<body @if(ctx.csrfHeaderJson() != null)hx-headers='${ctx.csrfHeaderJson()}'@endif>`

**Why it was necessary:** JTE's `ContentType.Html` rejects `@if` in an attribute-*name* position and
fails the build (which is the behaviour the plan wants — template errors must fail the build):

```
gg.jte.TemplateException: Failed to compile layout.jte, error at line 13:
Illegal HTML attribute name @if(ctx.csrfHeaderJson()! @if expressions in HTML attribute names are
not allowed. ... smart attributes will do just that
```

The replacement is the mechanism JTE's own error message points at:
`<body hx-headers="${ctx.csrfHeaderJson()}">`. JTE omits an attribute entirely when its value
expression is `null`, so the rendered output is identical to the plan's intent: the `hx-headers`
attribute appears only when a CSRF token is present.

## Review-fix pass (2026-07-14)

Seven verified findings from an external review were fixed, one commit each, TDD throughout.
What changed, beyond the literal instructions:

### 6. `Fmt.url` encodes spaces as `%20`, not URLEncoder's `+`

`URLEncoder.encode` is `application/x-www-form-urlencoded`, which maps a space to `+` and a
literal `+` to `%2B`. That decodes correctly in a servlet container's query string, but `+` is
only *conventionally* a space there, and it is wrong in any other URL component. `Fmt.url`
therefore post-processes `+` to `%20`, which every decoder reads as a space. This also keeps
the helper honest under MockMvc, whose `UriUtils.decode` does not translate `+` to a space —
a plain `URLEncoder` link would round-trip in production but not in the controller test.

### 7. A fourth template had the same unencoded-identifier defect

The review listed `executionsTable.jte`, `historyTable.jte` and `recurring.jte`. Grepping the
JTE tree as instructed also turned up `fragments/recentFailures.jte:12`, which builds the same
`?task=…&id=…` link from a history entry. It is fixed and tested alongside the other three.

### 8. Auto-configuration ordering hint is belt-and-braces

Both starters now declare `afterName = {DbSchedulerAutoConfiguration, DataSourceAutoConfiguration}`.
The `DataSource` back-off test passes without the second entry — Spring already evaluates
`DataSourceAutoConfiguration` first here — but `@ConditionalOnBean` is only reliable when the
auto-configuration that *defines* the bean is ordered earlier, so the guarantee is made
explicit rather than left to incidental sort order.

### 9. `ExecutionRepository.nextExecutionTime` and `HistoryRepository.latestForTask` are retained

The batched `nextExecutionTimes()` / `latestPerTask()` queries replaced them on the recurring
page, so neither has a production caller any more. They are kept because they remain part of
the repositories' public API and the review requires the dialect contract suite to exercise
every public query method; both are covered there on all dialects.

### Local build note (environment, not a repo change)

Gradle 8.14.3 cannot run on the JDK 25 that is currently first on this machine's `PATH`
(`./gradlew` fails with a bare `25.0.3`). The build was run with
`JAVA_HOME=~/.sdkman/candidates/java/17.0.19-tem`, which matches the project's Java 17
toolchain. No build file was changed.

## Known gaps

### SQL Server contract test is not verified on this machine (Apple Silicon / arm64)

`SqlServerContractTest` is written, compiles, and is wired into `:console-core:containerTest`
exactly as the plan specifies (`mcr.microsoft.com/mssql/server:2022-latest`), but SQL Server
cannot start under OrbStack's amd64 emulation on this arm64 host:

- `2022-latest` exits with code 1 during startup:
  `Unable to create a new asynchronous I/O context. Please increase sysctl fs.aio-max-nr`
  (`fs.aio-max-nr` is already at its maximum, 1048576 — the real cause is that `io_setup` is not
  serviceable under Rosetta emulation, not a tunable limit).
- `2025-latest` prints its banner and then hangs indefinitely without ever becoming ready.

The failure is entirely in container startup — no SQL from this project is ever executed — so it
proves nothing about the SQL Server dialect either way. The test is left in place at the tag the
plan specifies; the GitHub Actions workflow added in Task 19 runs on amd64 Linux runners, where
this image starts normally, so the dialect is covered in CI.

**Verified locally:** Postgres, MySQL, MariaDB, Oracle — 2 contract tests each, all passing.
**Unverified locally:** SQL Server (environment limitation, see above).
