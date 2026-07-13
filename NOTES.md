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
