# db-scheduler-console Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Spring Boot library (Boot 3 + Boot 4 starters) that auto-configures an htmx server-rendered dashboard for db-scheduler with live views, execution history, stats, and admin actions.

**Architecture:** One `console-core` module holds all controllers, services, JDBC repositories, precompiled JTE templates, and vendored static assets; two thin starter modules contain only auto-configuration. Live state is read from db-scheduler's task table via dialect-aware JDBC; history is captured into our own `dsc_execution_history` table by a `SchedulerListener`; mutations go only through `SchedulerClient`.

**Tech Stack:** Java 17, Gradle (Kotlin DSL), db-scheduler 16.12.0, Spring Boot 3.5.16 / 4.0.7, JTE 3.2.4 (precompiled), htmx 2.0.10 (vendored), JUnit 5, Testcontainers, JSoup, Awaitility.

## Global Constraints

- Java toolchain: **17**. Gradle wrapper: **8.14.3**.
- Group `io.github.logicsatinn`, version `0.1.0-SNAPSHOT`, package root `io.github.logicsatinn.dbscheduler.console`.
- Pinned versions: db-scheduler **16.12.0**, Spring Boot 3 BOM **3.5.16**, Spring Boot 4 BOM **4.0.7**, JTE **3.2.4**, htmx **2.0.10**, jsoup **1.18.3**, Testcontainers **1.21.3**, Awaitility **4.2.2**.
- db-scheduler's live table default name is **`scheduled_tasks`** (NOT `scheduled_executions`); always honor the configured `db-scheduler.table-name`.
- History table name is fixed: **`dsc_execution_history`**.
- Config property prefix: **`db-scheduler-console`**.
- The history listener **must never throw** into task execution — catch-all + WARN log.
- SQL must be ANSI except pagination: MySQL/MariaDB use ` LIMIT ? OFFSET ?`; all others use ` OFFSET ? ROWS FETCH NEXT ? ROWS ONLY` (always after an ORDER BY).
- No runtime network calls: htmx + CSS are served from the jar.
- UI colors are fixed, validated tokens (see Task 10 CSS). Chart outcome colors: succeeded `#0ca30c`, failed `#d03b3b` (same hex in light+dark; CVD-validated). Values/labels always use ink tokens, never series colors.
- Mutating endpoints are POST-only; `read-only=true` returns 403 and hides buttons.
- TDD: every code task writes the failing test first. Commit at the end of every task with the message given in its final step.
- All commits end with: `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`

## File Structure

```
db-scheduler-console/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/libs.versions.toml
├── .gitignore
├── .github/workflows/build.yml                            (Task 19)
├── LICENSE                                                (Task 19)
├── README.md                                              (Task 19)
├── console-core/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/java/io/github/logicsatinn/dbscheduler/console/
│       │   ├── ConsoleProperties.java                     (Task 10)
│       │   ├── ConsoleAvailability.java                   (Task 16)
│       │   ├── data/
│       │   │   ├── Dialect.java                           (Task 2)
│       │   │   ├── ExecutionState.java                    (Task 3)
│       │   │   ├── ExecutionRow.java                      (Task 3)
│       │   │   ├── ExecutionFilter.java                   (Task 3)
│       │   │   ├── SortColumn.java                        (Task 3)
│       │   │   ├── Page.java                              (Task 3)
│       │   │   ├── ExecutionRepository.java               (Task 3)
│       │   │   └── history/
│       │   │       ├── HistoryEntry.java                  (Task 4)
│       │   │       ├── HistoryFilter.java                 (Task 4)
│       │   │       └── HistoryRepository.java             (Task 4)
│       │   ├── service/
│       │   │   ├── ConsoleSchedulerListener.java          (Task 6)
│       │   │   ├── HistoryPurgeTask.java                  (Task 6)
│       │   │   ├── StatsService.java                      (Task 7)
│       │   │   ├── ExecutionActions.java                  (Task 8)
│       │   │   ├── RecurringTasksService.java             (Task 13)
│       │   │   └── TaskDataRenderer.java                  (Task 9)
│       │   └── web/
│       │       ├── TemplateRenderer.java                  (Task 10)
│       │       ├── CsrfSupport.java                       (Task 10)
│       │       ├── PageCtx.java                           (Task 10)
│       │       ├── PageCtxFactory.java                    (Task 10)
│       │       ├── Fmt.java                               (Task 10)
│       │       ├── StaticAssetsController.java            (Task 10)
│       │       ├── OverviewController.java                (Tasks 10–11)
│       │       ├── ChartVm.java                           (Task 11)
│       │       ├── ExecutionsController.java              (Task 12)
│       │       ├── RecurringController.java               (Task 13)
│       │       ├── HistoryController.java                 (Task 14)
│       │       ├── ActionsController.java                 (Task 15)
│       │       └── ConsoleAvailabilityInterceptor.java    (Task 16)
│       ├── main/jte/
│       │   ├── layout.jte                                 (Task 10)
│       │   ├── pages/overview.jte                         (Task 11)
│       │   ├── pages/executions.jte                       (Task 12)
│       │   ├── pages/executionDetail.jte                  (Task 12)
│       │   ├── pages/recurring.jte                        (Task 13)
│       │   ├── pages/history.jte                          (Task 14)
│       │   ├── pages/historySetup.jte                     (Task 14)
│       │   └── fragments/
│       │       ├── tiles.jte                              (Task 11)
│       │       ├── chart.jte                              (Task 11)
│       │       ├── recentFailures.jte                     (Task 11)
│       │       ├── executionsTable.jte                    (Task 12)
│       │       ├── historyTable.jte                       (Task 14)
│       │       └── flash.jte                              (Task 15)
│       ├── main/resources/db-scheduler-console/
│       │   ├── migrations/{postgresql,mysql,mariadb,sqlserver,oracle,h2}.sql   (Task 4)
│       │   └── static/{htmx.min.js,console.css}           (Task 10)
│       └── test/java/io/github/logicsatinn/dbscheduler/console/   (mirrors main)
│           └── test/resources/db-scheduler-schema/*.sql   (Tasks 3, 5)
├── console-spring-boot-3-starter/
│   ├── build.gradle.kts
│   └── src/main/java/io/github/logicsatinn/dbscheduler/console/boot3/
│       └── DbSchedulerConsoleAutoConfiguration.java       (Task 16)
│   └── src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
├── console-spring-boot-4-starter/                         (Task 17, mirrors boot3 in package …console.boot4)
└── examples/
    ├── boot3-example/                                     (Task 18)
    └── boot4-example/                                     (Task 18)
```

---

### Task 1: Gradle multi-module scaffold

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `.gitignore`
- Create: `console-core/build.gradle.kts`, `console-spring-boot-3-starter/build.gradle.kts`, `console-spring-boot-4-starter/build.gradle.kts`, `examples/boot3-example/build.gradle.kts`, `examples/boot4-example/build.gradle.kts`

**Interfaces:**
- Produces: module names `:console-core`, `:console-spring-boot-3-starter`, `:console-spring-boot-4-starter`, `:examples:boot3-example`, `:examples:boot4-example`; version-catalog aliases used by every later task.

- [ ] **Step 1: Write `settings.gradle.kts`**

```kotlin
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "db-scheduler-console"

include(
    "console-core",
    "console-spring-boot-3-starter",
    "console-spring-boot-4-starter",
    "examples:boot3-example",
    "examples:boot4-example",
)
```

- [ ] **Step 2: Write `gradle/libs.versions.toml`**

```toml
[versions]
dbScheduler = "16.12.0"
springBoot3 = "3.5.16"
springBoot4 = "4.0.7"
jte = "3.2.4"
jsoup = "1.18.3"
testcontainers = "1.21.3"
awaitility = "4.2.2"

[libraries]
dbScheduler = { module = "com.github.kagkarlsson:db-scheduler", version.ref = "dbScheduler" }
dbSchedulerBoot3Starter = { module = "com.github.kagkarlsson:db-scheduler-spring-boot-starter", version.ref = "dbScheduler" }
dbSchedulerBoot4Starter = { module = "com.github.kagkarlsson:db-scheduler-spring-boot-4-starter", version.ref = "dbScheduler" }
springBoot3Bom = { module = "org.springframework.boot:spring-boot-dependencies", version.ref = "springBoot3" }
springBoot4Bom = { module = "org.springframework.boot:spring-boot-dependencies", version.ref = "springBoot4" }
jteRuntime = { module = "gg.jte:jte-runtime", version.ref = "jte" }
jsoup = { module = "org.jsoup:jsoup", version.ref = "jsoup" }
awaitility = { module = "org.awaitility:awaitility", version.ref = "awaitility" }
testcontainersJunit = { module = "org.testcontainers:junit-jupiter", version.ref = "testcontainers" }
testcontainersPostgresql = { module = "org.testcontainers:postgresql", version.ref = "testcontainers" }
testcontainersMysql = { module = "org.testcontainers:mysql", version.ref = "testcontainers" }
testcontainersMariadb = { module = "org.testcontainers:mariadb", version.ref = "testcontainers" }
testcontainersMssqlserver = { module = "org.testcontainers:mssqlserver", version.ref = "testcontainers" }
testcontainersOracleFree = { module = "org.testcontainers:oracle-free", version.ref = "testcontainers" }
jdbcPostgresql = { module = "org.postgresql:postgresql", version = "42.7.7" }
jdbcMysql = { module = "com.mysql:mysql-connector-j", version = "9.3.0" }
jdbcMariadb = { module = "org.mariadb.jdbc:mariadb-java-client", version = "3.5.4" }
jdbcMssql = { module = "com.microsoft.sqlserver:mssql-jdbc", version = "12.10.1.jre11" }
jdbcOracle = { module = "com.oracle.database.jdbc:ojdbc11", version = "23.8.0.25.04" }

[plugins]
jte = { id = "gg.jte.gradle", version.ref = "jte" }
```

- [ ] **Step 3: Write root `build.gradle.kts` and `gradle.properties`**

`build.gradle.kts`:
```kotlin
plugins {
    base
}

allprojects {
    group = "io.github.logicsatinn"
    version = "0.1.0-SNAPSHOT"
}
```

`gradle.properties`:
```properties
org.gradle.parallel=true
org.gradle.caching=true
```

- [ ] **Step 4: Write `.gitignore`**

```
.gradle/
build/
.idea/
*.iml
.DS_Store
```

- [ ] **Step 5: Write `console-core/build.gradle.kts`** (JTE plugin block is added later, in Task 10)

```kotlin
plugins {
    `java-library`
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(17) }
    withSourcesJar()
}

dependencies {
    api(libs.dbScheduler)
    implementation(libs.jteRuntime)

    compileOnly(platform(libs.springBoot3Bom))
    compileOnly("org.springframework:spring-webmvc")
    compileOnly("org.springframework:spring-jdbc")
    compileOnly("org.springframework.boot:spring-boot")
    compileOnly("org.springframework.security:spring-security-web")
    compileOnly("jakarta.servlet:jakarta.servlet-api")
    compileOnly("org.slf4j:slf4j-api")

    testImplementation(platform(libs.springBoot3Bom))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework:spring-jdbc")
    testImplementation("org.springframework.security:spring-security-web")
    testImplementation("com.h2database:h2")
    testImplementation(libs.jsoup)
    testImplementation(libs.awaitility)
    testImplementation(libs.testcontainersJunit)
    testImplementation(libs.testcontainersPostgresql)
    testImplementation(libs.testcontainersMysql)
    testImplementation(libs.testcontainersMariadb)
    testImplementation(libs.testcontainersMssqlserver)
    testImplementation(libs.testcontainersOracleFree)
    testRuntimeOnly(libs.jdbcPostgresql)
    testRuntimeOnly(libs.jdbcMysql)
    testRuntimeOnly(libs.jdbcMariadb)
    testRuntimeOnly(libs.jdbcMssql)
    testRuntimeOnly(libs.jdbcOracle)
}

tasks.test {
    useJUnitPlatform {
        excludeTags("containers")
    }
}

val containerTest by tasks.registering(Test::class) {
    description = "Dialect matrix tests using Testcontainers"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("containers") }
    shouldRunAfter(tasks.test)
}
```

- [ ] **Step 6: Write `console-spring-boot-3-starter/build.gradle.kts`**

```kotlin
plugins {
    `java-library`
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(17) }
    withSourcesJar()
}

dependencies {
    api(project(":console-core"))
    api(libs.dbSchedulerBoot3Starter)

    compileOnly(platform(libs.springBoot3Bom))
    compileOnly("org.springframework.boot:spring-boot-autoconfigure")
    compileOnly("org.springframework:spring-webmvc")

    testImplementation(platform(libs.springBoot3Bom))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc")
    testImplementation("com.h2database:h2")
}

tasks.test {
    useJUnitPlatform()
}
```

- [ ] **Step 7: Write `console-spring-boot-4-starter/build.gradle.kts`** — identical to Step 6 except: `api(libs.dbSchedulerBoot4Starter)` and every `platform(libs.springBoot3Bom)` becomes `platform(libs.springBoot4Bom)`.

- [ ] **Step 8: Write example build files.**

`examples/boot3-example/build.gradle.kts`:
```kotlin
plugins {
    application
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(17) }
}

dependencies {
    implementation(platform(libs.springBoot3Bom))
    implementation(project(":console-spring-boot-3-starter"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    runtimeOnly("com.h2database:h2")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

application {
    mainClass = "io.github.logicsatinn.dbscheduler.console.example.boot3.DemoApp"
}

tasks.test {
    useJUnitPlatform()
}
```

`examples/boot4-example/build.gradle.kts`: identical except `springBoot3Bom` → `springBoot4Bom`, `:console-spring-boot-3-starter` → `:console-spring-boot-4-starter`, and mainClass package `…example.boot4.DemoApp`.

(Examples deliberately use the `application` plugin, not the Spring Boot Gradle plugin — applying two versions of the Boot plugin in one build conflicts. `@SpringBootApplication` apps run fine via `application`.)

- [ ] **Step 9: Generate the Gradle wrapper**

Run: `cd /Users/logicsatinn/work/github/logicsatinn/db-scheduler-console && gradle wrapper --gradle-version 8.14.3`
(If no system Gradle: `brew install gradle` first, or copy `gradle/wrapper/`, `gradlew`, `gradlew.bat` from any Gradle 8.14.x project and set `distributionUrl` to `8.14.3`.)
Expected: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties` created.

- [ ] **Step 10: Verify the build**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL` (all modules empty, nothing to compile).

- [ ] **Step 11: Commit**

```bash
git add -A
git commit -m "build: Gradle multi-module scaffold (core, boot3/boot4 starters, examples)"
```

---

### Task 2: Dialect detection and pagination

**Files:**
- Create: `console-core/src/main/java/io/github/logicsatinn/dbscheduler/console/data/Dialect.java`
- Test: `console-core/src/test/java/io/github/logicsatinn/dbscheduler/console/data/DialectTest.java`

**Interfaces:**
- Produces:
  - `enum Dialect { POSTGRES, MYSQL, MARIADB, SQLSERVER, ORACLE, H2 }`
  - `static Optional<Dialect> fromProductName(String productName)`
  - `static Optional<Dialect> fromDataSource(DataSource ds)`
  - `String paginationClause()` — append after ORDER BY
  - `Object[] paginationParams(int offset, int limit)` — bind order matches the clause
  - `String migrationResource()` — classpath path of the history DDL for this dialect

- [ ] **Step 1: Write the failing test**

```java
package io.github.logicsatinn.dbscheduler.console.data;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

class DialectTest {

    @Test
    void detectsKnownProductNames() {
        assertThat(Dialect.fromProductName("PostgreSQL")).contains(Dialect.POSTGRES);
        assertThat(Dialect.fromProductName("MySQL")).contains(Dialect.MYSQL);
        assertThat(Dialect.fromProductName("MariaDB")).contains(Dialect.MARIADB);
        // MariaDB servers can report "MySQL" wire compat strings that include "MariaDB"
        assertThat(Dialect.fromProductName("MySQL (MariaDB fork)")).contains(Dialect.MARIADB);
        assertThat(Dialect.fromProductName("Microsoft SQL Server")).contains(Dialect.SQLSERVER);
        assertThat(Dialect.fromProductName("Oracle")).contains(Dialect.ORACLE);
        assertThat(Dialect.fromProductName("H2")).contains(Dialect.H2);
        assertThat(Dialect.fromProductName("SQLite")).isEmpty();
        assertThat(Dialect.fromProductName(null)).isEmpty();
    }

    @Test
    void detectsFromDataSourceMetadata() {
        var ds = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2).generateUniqueName(true).build();
        assertThat(Dialect.fromDataSource(ds)).contains(Dialect.H2);
    }

    @Test
    void paginationVariants() {
        assertThat(Dialect.MYSQL.paginationClause()).isEqualTo(" LIMIT ? OFFSET ?");
        assertThat(Dialect.MYSQL.paginationParams(40, 20)).containsExactly(20, 40);
        assertThat(Dialect.MARIADB.paginationClause()).isEqualTo(" LIMIT ? OFFSET ?");
        assertThat(Dialect.POSTGRES.paginationClause())
                .isEqualTo(" OFFSET ? ROWS FETCH NEXT ? ROWS ONLY");
        assertThat(Dialect.POSTGRES.paginationParams(40, 20)).containsExactly(40, 20);
        assertThat(Dialect.ORACLE.paginationClause())
                .isEqualTo(" OFFSET ? ROWS FETCH NEXT ? ROWS ONLY");
    }

    @Test
    void migrationResourcesExistOnClasspath() {
        for (Dialect d : Dialect.values()) {
            assertThat(getClass().getClassLoader().getResource(d.migrationResource()))
                    .as("migration for %s", d).isNotNull();
        }
    }
}
```

Note: `migrationResourcesExistOnClasspath` will keep failing until Task 4 adds the SQL files — that is expected; mark it `@org.junit.jupiter.api.Disabled("until Task 4")` for this task and re-enable it in Task 4.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :console-core:test --tests 'io.github.logicsatinn.dbscheduler.console.data.DialectTest'`
Expected: FAIL — `Dialect` does not exist (compilation error).

- [ ] **Step 3: Implement `Dialect`**

```java
package io.github.logicsatinn.dbscheduler.console.data;

import java.sql.SQLException;
import java.util.Locale;
import java.util.Optional;
import javax.sql.DataSource;

public enum Dialect {
    POSTGRES, MYSQL, MARIADB, SQLSERVER, ORACLE, H2;

    public static Optional<Dialect> fromProductName(String productName) {
        if (productName == null) {
            return Optional.empty();
        }
        String p = productName.toLowerCase(Locale.ROOT);
        if (p.contains("postgres")) return Optional.of(POSTGRES);
        if (p.contains("mariadb")) return Optional.of(MARIADB); // before mysql: compat strings
        if (p.contains("mysql")) return Optional.of(MYSQL);
        if (p.contains("microsoft sql server")) return Optional.of(SQLSERVER);
        if (p.contains("oracle")) return Optional.of(ORACLE);
        if (p.contains("h2")) return Optional.of(H2);
        return Optional.empty();
    }

    public static Optional<Dialect> fromDataSource(DataSource ds) {
        try (var con = ds.getConnection()) {
            return fromProductName(con.getMetaData().getDatabaseProductName());
        } catch (SQLException e) {
            return Optional.empty();
        }
    }

    /** Appended after an ORDER BY clause. Bind with {@link #paginationParams}. */
    public String paginationClause() {
        return switch (this) {
            case MYSQL, MARIADB -> " LIMIT ? OFFSET ?";
            default -> " OFFSET ? ROWS FETCH NEXT ? ROWS ONLY";
        };
    }

    public Object[] paginationParams(int offset, int limit) {
        return switch (this) {
            case MYSQL, MARIADB -> new Object[] {limit, offset};
            default -> new Object[] {offset, limit};
        };
    }

    /** Classpath location of the dsc_execution_history DDL for this dialect. */
    public String migrationResource() {
        String file = switch (this) {
            case POSTGRES -> "postgresql";
            case MYSQL -> "mysql";
            case MARIADB -> "mariadb";
            case SQLSERVER -> "sqlserver";
            case ORACLE -> "oracle";
            case H2 -> "h2";
        };
        return "db-scheduler-console/migrations/" + file + ".sql";
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :console-core:test --tests 'io.github.logicsatinn.dbscheduler.console.data.DialectTest'`
Expected: PASS (with the migrations test disabled).

- [ ] **Step 5: Commit**

```bash
git add console-core
git commit -m "feat(core): database dialect detection and pagination variants"
```

---

### Task 3: Live execution repository

**Files:**
- Create: `console-core/src/main/java/io/github/logicsatinn/dbscheduler/console/data/ExecutionState.java`, `ExecutionRow.java`, `ExecutionFilter.java`, `SortColumn.java`, `Page.java`, `ExecutionRepository.java`
- Create: `console-core/src/test/resources/db-scheduler-schema/h2.sql`
- Test: `console-core/src/test/java/io/github/logicsatinn/dbscheduler/console/data/ExecutionRepositoryTest.java`

**Interfaces:**
- Consumes: `Dialect` (Task 2).
- Produces:
  - `enum ExecutionState { SCHEDULED, DUE, RUNNING, FAILING }`
  - `record ExecutionRow(String taskName, String instanceId, byte[] taskData, Instant executionTime, boolean picked, String pickedBy, Instant lastSuccess, Instant lastFailure, int consecutiveFailures, Instant lastHeartbeat, long version)` with `ExecutionState state(Instant now)`
  - `record ExecutionFilter(ExecutionState state, String taskName, String instanceContains, Instant from, Instant to, int page, int pageSize, SortColumn sort, boolean descending)` with `static ExecutionFilter none(int page, int pageSize)`
  - `enum SortColumn { EXECUTION_TIME, TASK_NAME, CONSECUTIVE_FAILURES, LAST_HEARTBEAT }` with `String column()`
  - `record Page<T>(List<T> items, int page, int pageSize, long total)` with `int totalPages()`
  - `class ExecutionRepository`: ctor `(DataSource, String tableName, Dialect)`; methods `Page<ExecutionRow> page(ExecutionFilter f, Instant now)`, `Optional<ExecutionRow> find(String taskName, String instanceId)`, `List<String> distinctTaskNames()`, `LiveCounts counts(Instant now)`, `record LiveCounts(long scheduled, long due, long running, long failing)` (nested in `ExecutionRepository`)
- State semantics (precedence order, mirrored in SQL and in `ExecutionRow.state`):
  - RUNNING: `picked = true`
  - FAILING: `picked = false AND COALESCE(consecutive_failures, 0) > 0`
  - DUE: `picked = false AND COALESCE(consecutive_failures, 0) = 0 AND execution_time <= now`
  - SCHEDULED: `picked = false AND COALESCE(consecutive_failures, 0) = 0 AND execution_time > now`

- [ ] **Step 1: Write the H2 test schema** `console-core/src/test/resources/db-scheduler-schema/h2.sql` (matches db-scheduler v16's H2 layout):

```sql
create table scheduled_tasks (
  task_name varchar(250) not null,
  task_instance varchar(250) not null,
  task_data blob,
  execution_time timestamp with time zone not null,
  picked boolean not null,
  picked_by varchar(50),
  last_success timestamp with time zone,
  last_failure timestamp with time zone,
  consecutive_failures int,
  last_heartbeat timestamp with time zone,
  version bigint not null,
  priority smallint,
  primary key (task_name, task_instance)
);
```

- [ ] **Step 2: Write the failing test**

```java
package io.github.logicsatinn.dbscheduler.console.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

class ExecutionRepositoryTest {

    static final Instant NOW = Instant.parse("2026-07-13T12:00:00Z");

    DataSource ds;
    JdbcTemplate jdbc;
    ExecutionRepository repo;

    @BeforeEach
    void setUp() {
        ds = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .addScript("db-scheduler-schema/h2.sql")
                .build();
        jdbc = new JdbcTemplate(ds);
        repo = new ExecutionRepository(ds, "scheduled_tasks", Dialect.H2);
    }

    void insert(String task, String id, Instant execTime, boolean picked, String pickedBy, Integer failures) {
        jdbc.update("""
            insert into scheduled_tasks
              (task_name, task_instance, task_data, execution_time, picked, picked_by, consecutive_failures, version)
            values (?, ?, null, ?, ?, ?, ?, 1)""",
            task, id, Timestamp.from(execTime), picked, pickedBy, failures);
    }

    @Test
    void filtersByDerivedState() {
        insert("task-a", "1", NOW.plus(1, ChronoUnit.HOURS), false, null, 0);   // SCHEDULED
        insert("task-a", "2", NOW.minus(1, ChronoUnit.MINUTES), false, null, 0); // DUE
        insert("task-b", "3", NOW.minus(1, ChronoUnit.MINUTES), true, "node-1", 0); // RUNNING
        insert("task-b", "4", NOW.plus(5, ChronoUnit.MINUTES), false, null, 3);  // FAILING

        for (var expected : List.of(
                java.util.Map.entry(ExecutionState.SCHEDULED, "1"),
                java.util.Map.entry(ExecutionState.DUE, "2"),
                java.util.Map.entry(ExecutionState.RUNNING, "3"),
                java.util.Map.entry(ExecutionState.FAILING, "4"))) {
            var filter = new ExecutionFilter(expected.getKey(), null, null, null, null,
                    0, 10, SortColumn.EXECUTION_TIME, false);
            Page<ExecutionRow> page = repo.page(filter, NOW);
            assertThat(page.items()).extracting(ExecutionRow::instanceId)
                    .as("state %s", expected.getKey())
                    .containsExactly(expected.getValue());
        }
    }

    @Test
    void filtersByTaskNameAndInstanceSearchAndTimeRange() {
        insert("email-send", "order-100", NOW.plus(10, ChronoUnit.MINUTES), false, null, 0);
        insert("email-send", "order-200", NOW.plus(20, ChronoUnit.MINUTES), false, null, 0);
        insert("report-gen", "order-100", NOW.plus(30, ChronoUnit.MINUTES), false, null, 0);

        var byTask = repo.page(new ExecutionFilter(null, "email-send", null, null, null,
                0, 10, SortColumn.EXECUTION_TIME, false), NOW);
        assertThat(byTask.total()).isEqualTo(2);

        var bySearch = repo.page(new ExecutionFilter(null, null, "ORDER-1", null, null,
                0, 10, SortColumn.EXECUTION_TIME, false), NOW);
        assertThat(bySearch.items()).extracting(ExecutionRow::taskName)
                .containsExactlyInAnyOrder("email-send", "report-gen");

        var byRange = repo.page(new ExecutionFilter(null, null, null,
                NOW.plus(15, ChronoUnit.MINUTES), NOW.plus(25, ChronoUnit.MINUTES),
                0, 10, SortColumn.EXECUTION_TIME, false), NOW);
        assertThat(byRange.items()).extracting(ExecutionRow::instanceId).containsExactly("order-200");
    }

    @Test
    void paginatesAndSorts() {
        for (int i = 0; i < 25; i++) {
            insert("bulk", "i-%02d".formatted(i), NOW.plus(i, ChronoUnit.MINUTES), false, null, 0);
        }
        var page0 = repo.page(ExecutionFilter.none(0, 10), NOW);
        var page2 = repo.page(ExecutionFilter.none(2, 10), NOW);
        assertThat(page0.total()).isEqualTo(25);
        assertThat(page0.totalPages()).isEqualTo(3);
        assertThat(page0.items()).hasSize(10);
        assertThat(page2.items()).hasSize(5);
        assertThat(page0.items().get(0).instanceId()).isEqualTo("i-00");

        var desc = repo.page(new ExecutionFilter(null, null, null, null, null,
                0, 10, SortColumn.EXECUTION_TIME, true), NOW);
        assertThat(desc.items().get(0).instanceId()).isEqualTo("i-24");
    }

    @Test
    void findCountsAndDistinctNames() {
        insert("task-a", "1", NOW.plus(1, ChronoUnit.HOURS), false, null, 0);
        insert("task-a", "2", NOW.minus(1, ChronoUnit.MINUTES), false, null, 0);
        insert("task-b", "3", NOW, true, "node-1", 0);
        insert("task-b", "4", NOW, false, null, 2);

        assertThat(repo.find("task-a", "1")).isPresent();
        assertThat(repo.find("task-a", "missing")).isEmpty();
        assertThat(repo.distinctTaskNames()).containsExactly("task-a", "task-b");

        var counts = repo.counts(NOW);
        assertThat(counts.scheduled()).isEqualTo(1);
        assertThat(counts.due()).isEqualTo(1);
        assertThat(counts.running()).isEqualTo(1);
        assertThat(counts.failing()).isEqualTo(1);
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew :console-core:test --tests '*ExecutionRepositoryTest'`
Expected: FAIL — classes do not exist.

- [ ] **Step 4: Implement the records and enums**

`ExecutionState.java`:
```java
package io.github.logicsatinn.dbscheduler.console.data;

public enum ExecutionState { SCHEDULED, DUE, RUNNING, FAILING }
```

`ExecutionRow.java`:
```java
package io.github.logicsatinn.dbscheduler.console.data;

import java.time.Instant;

public record ExecutionRow(
        String taskName,
        String instanceId,
        byte[] taskData,
        Instant executionTime,
        boolean picked,
        String pickedBy,
        Instant lastSuccess,
        Instant lastFailure,
        int consecutiveFailures,
        Instant lastHeartbeat,
        long version) {

    public ExecutionState state(Instant now) {
        if (picked) return ExecutionState.RUNNING;
        if (consecutiveFailures > 0) return ExecutionState.FAILING;
        return executionTime.isAfter(now) ? ExecutionState.SCHEDULED : ExecutionState.DUE;
    }
}
```

`SortColumn.java`:
```java
package io.github.logicsatinn.dbscheduler.console.data;

/** Whitelisted sortable columns — the enum is the SQL-injection guard. */
public enum SortColumn {
    EXECUTION_TIME("execution_time"),
    TASK_NAME("task_name"),
    CONSECUTIVE_FAILURES("consecutive_failures"),
    LAST_HEARTBEAT("last_heartbeat");

    private final String column;

    SortColumn(String column) { this.column = column; }

    public String column() { return column; }
}
```

`ExecutionFilter.java`:
```java
package io.github.logicsatinn.dbscheduler.console.data;

import java.time.Instant;

public record ExecutionFilter(
        ExecutionState state,
        String taskName,
        String instanceContains,
        Instant from,
        Instant to,
        int page,
        int pageSize,
        SortColumn sort,
        boolean descending) {

    public static ExecutionFilter none(int page, int pageSize) {
        return new ExecutionFilter(null, null, null, null, null,
                page, pageSize, SortColumn.EXECUTION_TIME, false);
    }
}
```

`Page.java`:
```java
package io.github.logicsatinn.dbscheduler.console.data;

import java.util.List;

public record Page<T>(List<T> items, int page, int pageSize, long total) {
    public int totalPages() {
        return (int) Math.ceil((double) total / pageSize);
    }
}
```

- [ ] **Step 5: Implement `ExecutionRepository`**

```java
package io.github.logicsatinn.dbscheduler.console.data;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public class ExecutionRepository {

    private static final String COLUMNS =
            "task_name, task_instance, task_data, execution_time, picked, picked_by, "
            + "last_success, last_failure, consecutive_failures, last_heartbeat, version";

    private final JdbcTemplate jdbc;
    private final String table;
    private final Dialect dialect;

    public ExecutionRepository(DataSource dataSource, String tableName, Dialect dialect) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.table = tableName;
        this.dialect = dialect;
    }

    public record LiveCounts(long scheduled, long due, long running, long failing) {}

    public Page<ExecutionRow> page(ExecutionFilter f, Instant now) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> params = new ArrayList<>();
        applyFilter(f, now, where, params);

        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + where, Long.class, params.toArray());

        String orderBy = " ORDER BY " + f.sort().column() + (f.descending() ? " DESC" : " ASC")
                + ", task_name, task_instance";
        String sql = "SELECT " + COLUMNS + " FROM " + table + where + orderBy
                + dialect.paginationClause();
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.addAll(Arrays.asList(
                dialect.paginationParams(f.page() * f.pageSize(), f.pageSize())));

        List<ExecutionRow> rows = jdbc.query(sql, ROW_MAPPER, pageParams.toArray());
        return new Page<>(rows, f.page(), f.pageSize(), total == null ? 0 : total);
    }

    public Optional<ExecutionRow> find(String taskName, String instanceId) {
        List<ExecutionRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM " + table
                        + " WHERE task_name = ? AND task_instance = ?",
                ROW_MAPPER, taskName, instanceId);
        return rows.stream().findFirst();
    }

    public List<String> distinctTaskNames() {
        return jdbc.queryForList(
                "SELECT DISTINCT task_name FROM " + table + " ORDER BY task_name", String.class);
    }

    public LiveCounts counts(Instant now) {
        return new LiveCounts(
                countState(ExecutionState.SCHEDULED, now),
                countState(ExecutionState.DUE, now),
                countState(ExecutionState.RUNNING, now),
                countState(ExecutionState.FAILING, now));
    }

    private long countState(ExecutionState state, Instant now) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> params = new ArrayList<>();
        appendStateCondition(state, now, where, params);
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + where, Long.class, params.toArray());
        return n == null ? 0 : n;
    }

    private void applyFilter(ExecutionFilter f, Instant now, StringBuilder where, List<Object> params) {
        if (f.state() != null) {
            appendStateCondition(f.state(), now, where, params);
        }
        if (f.taskName() != null && !f.taskName().isBlank()) {
            where.append(" AND task_name = ?");
            params.add(f.taskName());
        }
        if (f.instanceContains() != null && !f.instanceContains().isBlank()) {
            where.append(" AND LOWER(task_instance) LIKE ?");
            params.add("%" + f.instanceContains().toLowerCase(Locale.ROOT) + "%");
        }
        if (f.from() != null) {
            where.append(" AND execution_time >= ?");
            params.add(Timestamp.from(f.from()));
        }
        if (f.to() != null) {
            where.append(" AND execution_time <= ?");
            params.add(Timestamp.from(f.to()));
        }
    }

    private void appendStateCondition(ExecutionState state, Instant now,
            StringBuilder where, List<Object> params) {
        switch (state) {
            case RUNNING -> where.append(" AND picked = ?").append(add(params, true));
            case FAILING -> {
                where.append(" AND picked = ?").append(add(params, false));
                where.append(" AND COALESCE(consecutive_failures, 0) > 0");
            }
            case DUE -> {
                where.append(" AND picked = ?").append(add(params, false));
                where.append(" AND COALESCE(consecutive_failures, 0) = 0");
                where.append(" AND execution_time <= ?").append(add(params, Timestamp.from(now)));
            }
            case SCHEDULED -> {
                where.append(" AND picked = ?").append(add(params, false));
                where.append(" AND COALESCE(consecutive_failures, 0) = 0");
                where.append(" AND execution_time > ?").append(add(params, Timestamp.from(now)));
            }
        }
    }

    /** Adds a param and returns "" so it can be chained inside append(). */
    private static String add(List<Object> params, Object value) {
        params.add(value);
        return "";
    }

    private static final RowMapper<ExecutionRow> ROW_MAPPER = (rs, rowNum) -> new ExecutionRow(
            rs.getString("task_name"),
            rs.getString("task_instance"),
            rs.getBytes("task_data"),
            instant(rs, "execution_time"),
            rs.getBoolean("picked"),
            rs.getString("picked_by"),
            instant(rs, "last_success"),
            instant(rs, "last_failure"),
            rs.getInt("consecutive_failures"),
            instant(rs, "last_heartbeat"),
            rs.getLong("version"));

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./gradlew :console-core:test --tests '*ExecutionRepositoryTest'`
Expected: PASS (4 tests).

- [ ] **Step 7: Commit**

```bash
git add console-core
git commit -m "feat(core): live execution repository with state filters, search, paging"
```

---

### Task 4: History migrations and repository

**Files:**
- Create: `console-core/src/main/resources/db-scheduler-console/migrations/postgresql.sql`, `mysql.sql`, `mariadb.sql`, `sqlserver.sql`, `oracle.sql`, `h2.sql`
- Create: `console-core/src/main/java/io/github/logicsatinn/dbscheduler/console/data/history/HistoryEntry.java`, `HistoryFilter.java`, `HistoryRepository.java`
- Modify: `console-core/src/test/java/io/github/logicsatinn/dbscheduler/console/data/DialectTest.java` (re-enable `migrationResourcesExistOnClasspath`)
- Test: `console-core/src/test/java/io/github/logicsatinn/dbscheduler/console/data/history/HistoryRepositoryTest.java`

**Interfaces:**
- Consumes: `Dialect`, `Page` (Tasks 2–3).
- Produces:
  - `record HistoryEntry(long id, String taskName, String instanceId, HistoryEntry.Outcome outcome, Instant startedAt, Instant finishedAt, long durationMs, String exceptionClass, String exceptionMessage, String stacktrace, String pickedBy)`; `enum Outcome { SUCCEEDED, FAILED }`
  - `record HistoryFilter(String taskName, HistoryEntry.Outcome outcome, String textSearch, Instant from, Instant to, int page, int pageSize)`
  - `class HistoryRepository`: ctor `(DataSource, Dialect)`; methods:
    - `boolean tableExists()`
    - `void insert(HistoryEntry e)` (id ignored; message truncated to 2000 chars, stacktrace to 30000)
    - `Page<HistoryEntry> page(HistoryFilter f)`
    - `List<HistoryEntry> forInstance(String taskName, String instanceId, int limit)`
    - `int purgeOlderThan(Instant cutoff)`
    - `OutcomeCounts countsSince(Instant since)` — `record OutcomeCounts(long succeeded, long failed)` (nested)
    - `List<HistoryEntry> recentFailures(int limit)`
    - `List<OutcomePoint> window(Instant from, Instant to, int cap)` — `record OutcomePoint(Instant startedAt, HistoryEntry.Outcome outcome)` (nested)
    - `String createTableScript()` — raw text of this dialect's migration resource

- [ ] **Step 1: Write the six migration scripts**

`postgresql.sql`:
```sql
create table dsc_execution_history (
  id bigint generated always as identity primary key,
  task_name text not null,
  task_instance text not null,
  outcome varchar(16) not null,
  started_at timestamp with time zone not null,
  finished_at timestamp with time zone not null,
  duration_ms bigint not null,
  exception_class varchar(512),
  exception_message varchar(2000),
  stacktrace text,
  picked_by varchar(255)
);

create index dsc_eh_task_time_idx on dsc_execution_history (task_name, started_at);
create index dsc_eh_time_idx on dsc_execution_history (started_at);
create index dsc_eh_outcome_time_idx on dsc_execution_history (outcome, started_at);
```

`mysql.sql` (and `mariadb.sql`, identical content):
```sql
create table dsc_execution_history (
  id bigint auto_increment primary key,
  task_name varchar(250) not null,
  task_instance varchar(250) not null,
  outcome varchar(16) not null,
  started_at timestamp(6) not null,
  finished_at timestamp(6) not null,
  duration_ms bigint not null,
  exception_class varchar(512),
  exception_message varchar(2000),
  stacktrace mediumtext,
  picked_by varchar(255)
);

create index dsc_eh_task_time_idx on dsc_execution_history (task_name, started_at);
create index dsc_eh_time_idx on dsc_execution_history (started_at);
create index dsc_eh_outcome_time_idx on dsc_execution_history (outcome, started_at);
```

`sqlserver.sql`:
```sql
create table dsc_execution_history (
  id bigint identity(1,1) primary key,
  task_name nvarchar(250) not null,
  task_instance nvarchar(250) not null,
  outcome nvarchar(16) not null,
  started_at datetimeoffset not null,
  finished_at datetimeoffset not null,
  duration_ms bigint not null,
  exception_class nvarchar(512),
  exception_message nvarchar(2000),
  stacktrace nvarchar(max),
  picked_by nvarchar(255)
);

create index dsc_eh_task_time_idx on dsc_execution_history (task_name, started_at);
create index dsc_eh_time_idx on dsc_execution_history (started_at);
create index dsc_eh_outcome_time_idx on dsc_execution_history (outcome, started_at);
```

`oracle.sql`:
```sql
create table dsc_execution_history (
  id number(19) generated always as identity primary key,
  task_name varchar2(250) not null,
  task_instance varchar2(250) not null,
  outcome varchar2(16) not null,
  started_at timestamp(6) with time zone not null,
  finished_at timestamp(6) with time zone not null,
  duration_ms number(19) not null,
  exception_class varchar2(512),
  exception_message varchar2(2000),
  stacktrace clob,
  picked_by varchar2(255)
);

create index dsc_eh_task_time_idx on dsc_execution_history (task_name, started_at);
create index dsc_eh_time_idx on dsc_execution_history (started_at);
create index dsc_eh_outcome_time_idx on dsc_execution_history (outcome, started_at);
```

`h2.sql`:
```sql
create table dsc_execution_history (
  id bigint generated always as identity primary key,
  task_name varchar(250) not null,
  task_instance varchar(250) not null,
  outcome varchar(16) not null,
  started_at timestamp with time zone not null,
  finished_at timestamp with time zone not null,
  duration_ms bigint not null,
  exception_class varchar(512),
  exception_message varchar(2000),
  stacktrace clob,
  picked_by varchar(255)
);

create index dsc_eh_task_time_idx on dsc_execution_history (task_name, started_at);
create index dsc_eh_time_idx on dsc_execution_history (started_at);
create index dsc_eh_outcome_time_idx on dsc_execution_history (outcome, started_at);
```

- [ ] **Step 2: Re-enable `migrationResourcesExistOnClasspath` in `DialectTest`** (remove the `@Disabled` annotation) and run it.

Run: `./gradlew :console-core:test --tests '*DialectTest'`
Expected: PASS.

- [ ] **Step 3: Write the failing repository test**

```java
package io.github.logicsatinn.dbscheduler.console.data.history;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.logicsatinn.dbscheduler.console.data.Dialect;
import io.github.logicsatinn.dbscheduler.console.data.Page;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

class HistoryRepositoryTest {

    static final Instant NOW = Instant.parse("2026-07-13T12:00:00Z");

    DataSource ds;
    HistoryRepository repo;

    @BeforeEach
    void setUp() {
        ds = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .addScript("db-scheduler-console/migrations/h2.sql")
                .build();
        repo = new HistoryRepository(ds, Dialect.H2);
    }

    HistoryEntry entry(String task, String id, HistoryEntry.Outcome outcome, Instant started, String excMessage) {
        return new HistoryEntry(0, task, id, outcome, started, started.plusSeconds(2), 2000,
                outcome == HistoryEntry.Outcome.FAILED ? "java.lang.RuntimeException" : null,
                excMessage, excMessage == null ? null : "stack\n", "node-1");
    }

    @Test
    void tableExistsDetection() {
        assertThat(repo.tableExists()).isTrue();
        var emptyDs = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2).generateUniqueName(true).build();
        assertThat(new HistoryRepository(emptyDs, Dialect.H2).tableExists()).isFalse();
    }

    @Test
    void insertPageAndFilter() {
        repo.insert(entry("email", "1", HistoryEntry.Outcome.SUCCEEDED, NOW.minusSeconds(60), null));
        repo.insert(entry("email", "2", HistoryEntry.Outcome.FAILED, NOW.minusSeconds(50), "SMTP timeout"));
        repo.insert(entry("report", "3", HistoryEntry.Outcome.SUCCEEDED, NOW.minusSeconds(40), null));

        Page<HistoryEntry> all = repo.page(new HistoryFilter(null, null, null, null, null, 0, 10));
        assertThat(all.total()).isEqualTo(3);
        // newest first
        assertThat(all.items().get(0).instanceId()).isEqualTo("3");

        Page<HistoryEntry> failed = repo.page(new HistoryFilter(null, HistoryEntry.Outcome.FAILED, null, null, null, 0, 10));
        assertThat(failed.items()).hasSize(1);
        assertThat(failed.items().get(0).exceptionMessage()).isEqualTo("SMTP timeout");

        Page<HistoryEntry> text = repo.page(new HistoryFilter(null, null, "smtp", null, null, 0, 10));
        assertThat(text.items()).hasSize(1);

        Page<HistoryEntry> byTask = repo.page(new HistoryFilter("report", null, null, null, null, 0, 10));
        assertThat(byTask.items()).hasSize(1);
    }

    @Test
    void truncatesOversizedMessageAndStacktrace() {
        String longMsg = "x".repeat(5000);
        repo.insert(new HistoryEntry(0, "t", "1", HistoryEntry.Outcome.FAILED,
                NOW, NOW.plusSeconds(1), 1000, "E", longMsg, longMsg.repeat(10), "n"));
        HistoryEntry saved = repo.page(new HistoryFilter(null, null, null, null, null, 0, 1)).items().get(0);
        assertThat(saved.exceptionMessage()).hasSize(2000);
        assertThat(saved.stacktrace()).hasSize(30000);
    }

    @Test
    void forInstancePurgeCountsRecentFailuresWindow() {
        repo.insert(entry("email", "1", HistoryEntry.Outcome.FAILED, NOW.minus(30, ChronoUnit.DAYS), "old"));
        repo.insert(entry("email", "1", HistoryEntry.Outcome.SUCCEEDED, NOW.minusSeconds(90), null));
        repo.insert(entry("email", "1", HistoryEntry.Outcome.FAILED, NOW.minusSeconds(30), "recent"));
        repo.insert(entry("other", "9", HistoryEntry.Outcome.SUCCEEDED, NOW.minusSeconds(10), null));

        assertThat(repo.forInstance("email", "1", 10)).hasSize(3);
        assertThat(repo.forInstance("email", "1", 2)).hasSize(2);

        var counts = repo.countsSince(NOW.minus(1, ChronoUnit.DAYS));
        assertThat(counts.succeeded()).isEqualTo(2);
        assertThat(counts.failed()).isEqualTo(1);

        assertThat(repo.recentFailures(5)).extracting(HistoryEntry::exceptionMessage)
                .containsExactly("recent", "old");

        assertThat(repo.window(NOW.minus(1, ChronoUnit.HOURS), NOW, 1000)).hasSize(3);

        int purged = repo.purgeOlderThan(NOW.minus(14, ChronoUnit.DAYS));
        assertThat(purged).isEqualTo(1);
        assertThat(repo.page(new HistoryFilter(null, null, null, null, null, 0, 10)).total()).isEqualTo(3);
    }

    @Test
    void createTableScriptReturnsDialectDdl() {
        assertThat(repo.createTableScript()).contains("create table dsc_execution_history");
    }
}
```

- [ ] **Step 4: Run the test to verify it fails**

Run: `./gradlew :console-core:test --tests '*HistoryRepositoryTest'`
Expected: FAIL — classes do not exist.

- [ ] **Step 5: Implement the records**

`HistoryEntry.java`:
```java
package io.github.logicsatinn.dbscheduler.console.data.history;

import java.time.Instant;

public record HistoryEntry(
        long id,
        String taskName,
        String instanceId,
        Outcome outcome,
        Instant startedAt,
        Instant finishedAt,
        long durationMs,
        String exceptionClass,
        String exceptionMessage,
        String stacktrace,
        String pickedBy) {

    public enum Outcome { SUCCEEDED, FAILED }
}
```

`HistoryFilter.java`:
```java
package io.github.logicsatinn.dbscheduler.console.data.history;

import java.time.Instant;

public record HistoryFilter(
        String taskName,
        HistoryEntry.Outcome outcome,
        String textSearch,
        Instant from,
        Instant to,
        int page,
        int pageSize) {}
```

- [ ] **Step 6: Implement `HistoryRepository`**

```java
package io.github.logicsatinn.dbscheduler.console.data.history;

import io.github.logicsatinn.dbscheduler.console.data.Dialect;
import io.github.logicsatinn.dbscheduler.console.data.Page;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public class HistoryRepository {

    public static final String TABLE = "dsc_execution_history";
    static final int MAX_MESSAGE = 2000;
    static final int MAX_STACKTRACE = 30000;

    private static final String COLUMNS =
            "id, task_name, task_instance, outcome, started_at, finished_at, duration_ms, "
            + "exception_class, exception_message, stacktrace, picked_by";

    private final JdbcTemplate jdbc;
    private final Dialect dialect;

    public HistoryRepository(DataSource dataSource, Dialect dialect) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.dialect = dialect;
    }

    public record OutcomeCounts(long succeeded, long failed) {}

    public record OutcomePoint(Instant startedAt, HistoryEntry.Outcome outcome) {}

    public boolean tableExists() {
        try {
            jdbc.queryForObject("SELECT COUNT(*) FROM " + TABLE + " WHERE 1=0", Long.class);
            return true;
        } catch (DataAccessException e) {
            return false;
        }
    }

    public void insert(HistoryEntry e) {
        jdbc.update("INSERT INTO " + TABLE
                        + " (task_name, task_instance, outcome, started_at, finished_at, duration_ms,"
                        + " exception_class, exception_message, stacktrace, picked_by)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                e.taskName(), e.instanceId(), e.outcome().name(),
                Timestamp.from(e.startedAt()), Timestamp.from(e.finishedAt()), e.durationMs(),
                truncate(e.exceptionClass(), 512), truncate(e.exceptionMessage(), MAX_MESSAGE),
                truncate(e.stacktrace(), MAX_STACKTRACE), e.pickedBy());
    }

    public Page<HistoryEntry> page(HistoryFilter f) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (f.taskName() != null && !f.taskName().isBlank()) {
            where.append(" AND task_name = ?");
            params.add(f.taskName());
        }
        if (f.outcome() != null) {
            where.append(" AND outcome = ?");
            params.add(f.outcome().name());
        }
        if (f.textSearch() != null && !f.textSearch().isBlank()) {
            where.append(" AND (LOWER(task_instance) LIKE ? OR LOWER(exception_class) LIKE ?"
                    + " OR LOWER(exception_message) LIKE ?)");
            String like = "%" + f.textSearch().toLowerCase(Locale.ROOT) + "%";
            params.add(like);
            params.add(like);
            params.add(like);
        }
        if (f.from() != null) {
            where.append(" AND started_at >= ?");
            params.add(Timestamp.from(f.from()));
        }
        if (f.to() != null) {
            where.append(" AND started_at <= ?");
            params.add(Timestamp.from(f.to()));
        }

        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + TABLE + where, Long.class, params.toArray());

        String sql = "SELECT " + COLUMNS + " FROM " + TABLE + where
                + " ORDER BY started_at DESC, id DESC" + dialect.paginationClause();
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.addAll(Arrays.asList(
                dialect.paginationParams(f.page() * f.pageSize(), f.pageSize())));

        List<HistoryEntry> rows = jdbc.query(sql, ROW_MAPPER, pageParams.toArray());
        return new Page<>(rows, f.page(), f.pageSize(), total == null ? 0 : total);
    }

    public List<HistoryEntry> forInstance(String taskName, String instanceId, int limit) {
        String sql = "SELECT " + COLUMNS + " FROM " + TABLE
                + " WHERE task_name = ? AND task_instance = ?"
                + " ORDER BY started_at DESC, id DESC" + dialect.paginationClause();
        List<Object> params = new ArrayList<>(List.of(taskName, instanceId));
        params.addAll(Arrays.asList(dialect.paginationParams(0, limit)));
        return jdbc.query(sql, ROW_MAPPER, params.toArray());
    }

    public int purgeOlderThan(Instant cutoff) {
        return jdbc.update("DELETE FROM " + TABLE + " WHERE started_at < ?",
                Timestamp.from(cutoff));
    }

    public OutcomeCounts countsSince(Instant since) {
        long succeeded = countOutcome("SUCCEEDED", since);
        long failed = countOutcome("FAILED", since);
        return new OutcomeCounts(succeeded, failed);
    }

    private long countOutcome(String outcome, Instant since) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + TABLE + " WHERE outcome = ? AND started_at >= ?",
                Long.class, outcome, Timestamp.from(since));
        return n == null ? 0 : n;
    }

    public List<HistoryEntry> recentFailures(int limit) {
        String sql = "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE outcome = 'FAILED'"
                + " ORDER BY started_at DESC, id DESC" + dialect.paginationClause();
        return jdbc.query(sql, ROW_MAPPER, dialect.paginationParams(0, limit));
    }

    public List<OutcomePoint> window(Instant from, Instant to, int cap) {
        String sql = "SELECT started_at, outcome FROM " + TABLE
                + " WHERE started_at >= ? AND started_at < ?"
                + " ORDER BY started_at ASC, id ASC" + dialect.paginationClause();
        List<Object> params = new ArrayList<>(List.of(Timestamp.from(from), Timestamp.from(to)));
        params.addAll(Arrays.asList(dialect.paginationParams(0, cap)));
        return jdbc.query(sql, (rs, i) -> new OutcomePoint(
                rs.getTimestamp("started_at").toInstant(),
                HistoryEntry.Outcome.valueOf(rs.getString("outcome"))), params.toArray());
    }

    public String createTableScript() {
        String resource = dialect.migrationResource();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing migration resource " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static final RowMapper<HistoryEntry> ROW_MAPPER = (rs, rowNum) -> new HistoryEntry(
            rs.getLong("id"),
            rs.getString("task_name"),
            rs.getString("task_instance"),
            HistoryEntry.Outcome.valueOf(rs.getString("outcome")),
            instant(rs, "started_at"),
            instant(rs, "finished_at"),
            rs.getLong("duration_ms"),
            rs.getString("exception_class"),
            rs.getString("exception_message"),
            rs.getString("stacktrace"),
            rs.getString("picked_by"));

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }
}
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./gradlew :console-core:test --tests '*HistoryRepositoryTest' --tests '*DialectTest'`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add console-core
git commit -m "feat(core): execution history table migrations and repository"
```

---

### Task 5: Dialect matrix container tests

**Files:**
- Create: `console-core/src/test/resources/db-scheduler-schema/postgresql.sql`, `mysql.sql`, `mariadb.sql`, `sqlserver.sql`, `oracle.sql` (live-table DDL per dialect, mirrors db-scheduler v16)
- Create: `console-core/src/test/java/io/github/logicsatinn/dbscheduler/console/data/RepositoryContractTest.java`
- Create: `…/data/PostgresContractTest.java`, `MysqlContractTest.java`, `MariadbContractTest.java`, `SqlServerContractTest.java`, `OracleContractTest.java`

**Interfaces:**
- Consumes: `ExecutionRepository`, `HistoryRepository`, `Dialect` (Tasks 2–4).
- Produces: nothing new — proves the repositories against every supported database. Tagged `containers`, excluded from `./gradlew test`, run by `./gradlew :console-core:containerTest`.

- [ ] **Step 1: Write the live-table DDL fixtures** (same columns as `h2.sql` from Task 3, typed per dialect):

`postgresql.sql`:
```sql
create table scheduled_tasks (
  task_name text not null,
  task_instance text not null,
  task_data bytea,
  execution_time timestamp with time zone not null,
  picked boolean not null,
  picked_by text,
  last_success timestamp with time zone,
  last_failure timestamp with time zone,
  consecutive_failures int,
  last_heartbeat timestamp with time zone,
  version bigint not null,
  priority smallint,
  primary key (task_name, task_instance)
);
```

`mysql.sql` (and `mariadb.sql`, identical):
```sql
create table scheduled_tasks (
  task_name varchar(250) not null,
  task_instance varchar(250) not null,
  task_data blob,
  execution_time timestamp(6) not null,
  picked boolean not null,
  picked_by varchar(50),
  last_success timestamp(6) null,
  last_failure timestamp(6) null,
  consecutive_failures int,
  last_heartbeat timestamp(6) null,
  version bigint not null,
  priority smallint,
  primary key (task_name, task_instance)
);
```

`sqlserver.sql`:
```sql
create table scheduled_tasks (
  task_name nvarchar(250) not null,
  task_instance nvarchar(250) not null,
  task_data varbinary(max),
  execution_time datetimeoffset not null,
  picked bit not null,
  picked_by nvarchar(50),
  last_success datetimeoffset,
  last_failure datetimeoffset,
  consecutive_failures int,
  last_heartbeat datetimeoffset,
  version bigint not null,
  priority smallint,
  primary key (task_name, task_instance)
);
```

`oracle.sql`:
```sql
create table scheduled_tasks (
  task_name varchar2(250) not null,
  task_instance varchar2(250) not null,
  task_data blob,
  execution_time timestamp(6) with time zone not null,
  picked number(1,0) not null,
  picked_by varchar2(50),
  last_success timestamp(6) with time zone,
  last_failure timestamp(6) with time zone,
  consecutive_failures number(10,0),
  last_heartbeat timestamp(6) with time zone,
  version number(19,0) not null,
  priority number(4,0),
  primary key (task_name, task_instance)
)
```
(Oracle script has no trailing semicolon-separated statements issue: one statement, no trailing `;`.)

- [ ] **Step 2: Write the abstract contract test**

```java
package io.github.logicsatinn.dbscheduler.console.data;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.logicsatinn.dbscheduler.console.data.history.HistoryEntry;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryFilter;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** Contract every dialect must satisfy. Subclasses supply a live container. */
public abstract class RepositoryContractTest {

    static final Instant NOW = Instant.parse("2026-07-13T12:00:00Z");

    protected abstract DataSource dataSource();

    protected abstract Dialect dialect();

    static DataSource simpleDataSource(String url, String user, String password) {
        return new DriverManagerDataSource(url, user, password);
    }

    static void applyScripts(DataSource ds, String... classpathScripts) {
        var populator = new ResourceDatabasePopulator();
        for (String s : classpathScripts) {
            populator.addScript(new ClassPathResource(s));
        }
        populator.execute(ds);
    }

    JdbcTemplate jdbc;
    ExecutionRepository executions;
    HistoryRepository history;

    @BeforeEach
    void cleanAndWire() {
        jdbc = new JdbcTemplate(dataSource());
        jdbc.update("delete from scheduled_tasks");
        jdbc.update("delete from dsc_execution_history");
        executions = new ExecutionRepository(dataSource(), "scheduled_tasks", dialect());
        history = new HistoryRepository(dataSource(), dialect());
    }

    void insertExecution(String task, String id, Instant execTime, boolean picked, Integer failures) {
        jdbc.update("""
            insert into scheduled_tasks
              (task_name, task_instance, task_data, execution_time, picked, picked_by, consecutive_failures, version)
            values (?, ?, null, ?, ?, ?, ?, 1)""",
            task, id, Timestamp.from(execTime), picked, picked ? "node-1" : null, failures);
    }

    @Test
    void liveStateFiltersAndPagination() {
        insertExecution("t", "scheduled", NOW.plus(1, ChronoUnit.HOURS), false, 0);
        insertExecution("t", "due", NOW.minus(1, ChronoUnit.MINUTES), false, 0);
        insertExecution("t", "running", NOW, true, 0);
        insertExecution("t", "failing", NOW.plus(5, ChronoUnit.MINUTES), false, 2);

        for (ExecutionState state : ExecutionState.values()) {
            var page = executions.page(new ExecutionFilter(state, null, null, null, null,
                    0, 10, SortColumn.EXECUTION_TIME, false), NOW);
            assertThat(page.items()).as("state %s", state).hasSize(1);
        }

        for (int i = 0; i < 12; i++) {
            insertExecution("bulk", "i-%02d".formatted(i), NOW.plus(i, ChronoUnit.HOURS), false, 0);
        }
        var page1 = executions.page(new ExecutionFilter(null, "bulk", null, null, null,
                1, 5, SortColumn.EXECUTION_TIME, false), NOW);
        assertThat(page1.total()).isEqualTo(12);
        assertThat(page1.items()).extracting(ExecutionRow::instanceId).first().isEqualTo("i-05");

        var counts = executions.counts(NOW);
        assertThat(counts.running()).isEqualTo(1);
        assertThat(counts.failing()).isEqualTo(1);
    }

    @Test
    void historyRoundTrip() {
        history.insert(new HistoryEntry(0, "email", "1", HistoryEntry.Outcome.FAILED,
                NOW.minusSeconds(30), NOW.minusSeconds(28), 2000,
                "java.lang.RuntimeException", "boom", "stack", "node-1"));
        history.insert(new HistoryEntry(0, "email", "1", HistoryEntry.Outcome.SUCCEEDED,
                NOW.minus(20, ChronoUnit.DAYS), NOW.minus(20, ChronoUnit.DAYS).plusSeconds(1),
                1000, null, null, null, "node-1"));

        assertThat(history.tableExists()).isTrue();
        assertThat(history.page(new HistoryFilter(null, null, "boom", null, null, 0, 10)).items()).hasSize(1);
        assertThat(history.forInstance("email", "1", 10)).hasSize(2);
        assertThat(history.recentFailures(5)).hasSize(1);
        assertThat(history.countsSince(NOW.minus(1, ChronoUnit.DAYS)).failed()).isEqualTo(1);
        assertThat(history.window(NOW.minus(1, ChronoUnit.HOURS), NOW, 100)).hasSize(1);
        assertThat(history.purgeOlderThan(NOW.minus(14, ChronoUnit.DAYS))).isEqualTo(1);
    }
}
```

- [ ] **Step 3: Write the five container subclasses**

`PostgresContractTest.java`:
```java
package io.github.logicsatinn.dbscheduler.console.data;

import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("containers")
@Testcontainers
class PostgresContractTest extends RepositoryContractTest {

    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16-alpine");

    static DataSource ds;

    @BeforeAll
    static void schema() {
        ds = simpleDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        applyScripts(ds, "db-scheduler-schema/postgresql.sql",
                "db-scheduler-console/migrations/postgresql.sql");
    }

    @Override protected DataSource dataSource() { return ds; }

    @Override protected Dialect dialect() { return Dialect.POSTGRES; }
}
```

`MysqlContractTest.java` — same shape with:
```java
@Container
static final MySQLContainer<?> DB = new MySQLContainer<>("mysql:8.4");
// scripts: "db-scheduler-schema/mysql.sql", "db-scheduler-console/migrations/mysql.sql"
// dialect: Dialect.MYSQL
```

`MariadbContractTest.java`:
```java
@Container
static final MariaDBContainer<?> DB = new MariaDBContainer<>("mariadb:11.4");
// scripts: "db-scheduler-schema/mariadb.sql", "db-scheduler-console/migrations/mariadb.sql"
// dialect: Dialect.MARIADB
```

`SqlServerContractTest.java`:
```java
@Container
static final MSSQLServerContainer<?> DB =
        new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-latest").acceptLicense();
// scripts: "db-scheduler-schema/sqlserver.sql", "db-scheduler-console/migrations/sqlserver.sql"
// dialect: Dialect.SQLSERVER
```

`OracleContractTest.java`:
```java
@Container
static final OracleContainer DB = new OracleContainer("gvenzl/oracle-free:23-slim-faststart");
// import org.testcontainers.oracle.OracleContainer  (org.testcontainers:oracle-free module)
// scripts: "db-scheduler-schema/oracle.sql", "db-scheduler-console/migrations/oracle.sql"
// dialect: Dialect.ORACLE
```

- [ ] **Step 4: Run the fastest container test to prove the harness** (requires Docker running)

Run: `./gradlew :console-core:containerTest --tests '*PostgresContractTest'`
Expected: PASS (2 tests). If Docker is not running, start it first.

- [ ] **Step 5: Run the full matrix once**

Run: `./gradlew :console-core:containerTest`
Expected: PASS — 5 classes × 2 tests. (MSSQL and Oracle images are large; first run downloads them. If a specific image tag is unavailable on this machine's architecture, prefer bumping the tag over skipping the dialect.)

- [ ] **Step 6: Verify plain `test` still excludes containers**

Run: `./gradlew :console-core:test`
Expected: PASS, and no `*ContractTest` classes in the report.

- [ ] **Step 7: Commit**

```bash
git add console-core
git commit -m "test(core): repository contract tests across all six database dialects"
```

---

### Task 6: History listener and purge task

**Files:**
- Create: `console-core/src/main/java/io/github/logicsatinn/dbscheduler/console/service/ConsoleSchedulerListener.java`
- Create: `console-core/src/main/java/io/github/logicsatinn/dbscheduler/console/service/HistoryPurgeTask.java`
- Test: `console-core/src/test/java/io/github/logicsatinn/dbscheduler/console/service/ConsoleSchedulerListenerTest.java`
- Test: `console-core/src/test/java/io/github/logicsatinn/dbscheduler/console/service/SchedulerHistoryIntegrationTest.java`

**Interfaces:**
- Consumes: `HistoryRepository`, `HistoryEntry` (Task 4); db-scheduler `AbstractSchedulerListener`, `ExecutionComplete` (verified: `getExecution()`, `getTimeDone()`, `getDuration()`, `getResult()` → `ExecutionComplete.Result.OK/FAILED`, `getCause()`); `Execution.getTaskName()`, `Execution.getId()`, public field `Execution.pickedBy`.
- Produces:
  - `class ConsoleSchedulerListener extends AbstractSchedulerListener` — ctor `(HistoryRepository)`; overrides `onExecutionComplete`; **never throws**.
  - `final class HistoryPurgeTask` — `static final String NAME = "console-history-purge"`; `static RecurringTask<Void> create(HistoryRepository history, Duration retention, Clock clock)`.

- [ ] **Step 1: Write the failing unit test**

```java
package io.github.logicsatinn.dbscheduler.console.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.github.kagkarlsson.scheduler.task.Execution;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryEntry;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ConsoleSchedulerListenerTest {

    final HistoryRepository repo = mock(HistoryRepository.class);
    final ConsoleSchedulerListener listener = new ConsoleSchedulerListener(repo);

    static Execution execution() {
        return new Execution(Instant.parse("2026-07-13T12:00:00Z"),
                new TaskInstance<>("email", "42"));
    }

    @Test
    void recordsSuccess() {
        Instant started = Instant.parse("2026-07-13T12:00:00Z");
        Instant done = started.plusSeconds(3);
        listener.onExecutionComplete(ExecutionComplete.success(execution(), started, done));

        var captor = ArgumentCaptor.forClass(HistoryEntry.class);
        verify(repo).insert(captor.capture());
        var e = captor.getValue();
        org.assertj.core.api.Assertions.assertThat(e.taskName()).isEqualTo("email");
        org.assertj.core.api.Assertions.assertThat(e.instanceId()).isEqualTo("42");
        org.assertj.core.api.Assertions.assertThat(e.outcome()).isEqualTo(HistoryEntry.Outcome.SUCCEEDED);
        org.assertj.core.api.Assertions.assertThat(e.durationMs()).isEqualTo(3000);
        org.assertj.core.api.Assertions.assertThat(e.startedAt()).isEqualTo(started);
        org.assertj.core.api.Assertions.assertThat(e.finishedAt()).isEqualTo(done);
        org.assertj.core.api.Assertions.assertThat(e.exceptionClass()).isNull();
    }

    @Test
    void recordsFailureWithCause() {
        Instant started = Instant.parse("2026-07-13T12:00:00Z");
        listener.onExecutionComplete(ExecutionComplete.failure(
                execution(), started, started.plusSeconds(1),
                new IllegalStateException("boom")));

        var captor = ArgumentCaptor.forClass(HistoryEntry.class);
        verify(repo).insert(captor.capture());
        var e = captor.getValue();
        org.assertj.core.api.Assertions.assertThat(e.outcome()).isEqualTo(HistoryEntry.Outcome.FAILED);
        org.assertj.core.api.Assertions.assertThat(e.exceptionClass()).isEqualTo("java.lang.IllegalStateException");
        org.assertj.core.api.Assertions.assertThat(e.exceptionMessage()).isEqualTo("boom");
        org.assertj.core.api.Assertions.assertThat(e.stacktrace()).contains("IllegalStateException");
    }

    @Test
    void neverThrowsWhenRepositoryFails() {
        doThrow(new RuntimeException("db down")).when(repo).insert(any());
        Instant started = Instant.now();
        assertThatCode(() -> listener.onExecutionComplete(
                ExecutionComplete.success(execution(), started, started.plusSeconds(1))))
                .doesNotThrowAnyException();
    }
}
```

(If the `Execution` two-arg constructor differs in 16.12.0, check `Execution.java` in the IDE and use its shortest public constructor — the test only needs taskInstance + executionTime.)

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :console-core:test --tests '*ConsoleSchedulerListenerTest'`
Expected: FAIL — `ConsoleSchedulerListener` does not exist.

- [ ] **Step 3: Implement the listener**

```java
package io.github.logicsatinn.dbscheduler.console.service;

import com.github.kagkarlsson.scheduler.event.AbstractSchedulerListener;
import com.github.kagkarlsson.scheduler.task.Execution;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryEntry;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryRepository;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records finished executions into dsc_execution_history.
 * MUST never propagate exceptions — a history failure must not affect task execution.
 */
public class ConsoleSchedulerListener extends AbstractSchedulerListener {

    private static final Logger LOG = LoggerFactory.getLogger(ConsoleSchedulerListener.class);

    private final HistoryRepository history;

    public ConsoleSchedulerListener(HistoryRepository history) {
        this.history = history;
    }

    @Override
    public void onExecutionComplete(ExecutionComplete complete) {
        try {
            Execution execution = complete.getExecution();
            Instant finished = complete.getTimeDone();
            Instant started = finished.minus(complete.getDuration());
            boolean ok = complete.getResult() == ExecutionComplete.Result.OK;
            Throwable cause = complete.getCause().orElse(null);
            history.insert(new HistoryEntry(
                    0,
                    execution.getTaskName(),
                    execution.getId(),
                    ok ? HistoryEntry.Outcome.SUCCEEDED : HistoryEntry.Outcome.FAILED,
                    started,
                    finished,
                    complete.getDuration().toMillis(),
                    cause == null ? null : cause.getClass().getName(),
                    cause == null ? null : cause.getMessage(),
                    cause == null ? null : stacktraceOf(cause),
                    execution.pickedBy));
        } catch (RuntimeException e) {
            LOG.warn("db-scheduler-console: failed to record execution history", e);
        }
    }

    private static String stacktraceOf(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
```

- [ ] **Step 4: Implement the purge task**

```java
package io.github.logicsatinn.dbscheduler.console.service;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryRepository;
import java.time.Clock;
import java.time.Duration;

/** Daily recurring db-scheduler task that enforces history retention. */
public final class HistoryPurgeTask {

    public static final String NAME = "console-history-purge";

    private HistoryPurgeTask() {}

    public static RecurringTask<Void> create(HistoryRepository history, Duration retention, Clock clock) {
        return Tasks.recurring(NAME, FixedDelay.ofHours(24))
                .execute((instance, ctx) ->
                        history.purgeOlderThan(clock.instant().minus(retention)));
    }
}
```

- [ ] **Step 5: Run unit tests**

Run: `./gradlew :console-core:test --tests '*ConsoleSchedulerListenerTest'`
Expected: PASS (3 tests).

- [ ] **Step 6: Write the failing integration test** (real scheduler, real H2)

```java
package io.github.logicsatinn.dbscheduler.console.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import io.github.logicsatinn.dbscheduler.console.data.Dialect;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryEntry;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryFilter;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryRepository;
import java.time.Duration;
import java.time.Instant;
import javax.sql.DataSource;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

class SchedulerHistoryIntegrationTest {

    Scheduler scheduler;

    @AfterEach
    void stop() {
        if (scheduler != null) scheduler.stop();
    }

    @Test
    void realExecutionsAreRecordedAndFailuresNeverBreakTasks() {
        DataSource ds = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .addScript("db-scheduler-schema/h2.sql")
                .addScript("db-scheduler-console/migrations/h2.sql")
                .build();
        HistoryRepository history = new HistoryRepository(ds, Dialect.H2);

        OneTimeTask<Void> ok = Tasks.oneTime("itest-ok").execute((inst, ctx) -> {});
        OneTimeTask<Void> boom = Tasks.oneTime("itest-boom").execute((inst, ctx) -> {
            throw new RuntimeException("intentional");
        });

        scheduler = Scheduler.create(ds, ok, boom)
                .pollingInterval(Duration.ofMillis(100))
                .addSchedulerListener(new ConsoleSchedulerListener(history))
                .build();
        scheduler.start();

        scheduler.scheduleIfNotExists(ok.instance("1"), Instant.now());
        scheduler.scheduleIfNotExists(boom.instance("2"), Instant.now());

        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            var page = history.page(new HistoryFilter(null, null, null, null, null, 0, 10));
            assertThat(page.total()).isEqualTo(2);
        });

        var failures = history.recentFailures(5);
        assertThat(failures).hasSize(1);
        assertThat(failures.get(0).taskName()).isEqualTo("itest-boom");
        assertThat(failures.get(0).exceptionMessage()).isEqualTo("intentional");
    }
}
```

- [ ] **Step 7: Run the integration test**

Run: `./gradlew :console-core:test --tests '*SchedulerHistoryIntegrationTest'`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add console-core
git commit -m "feat(core): scheduler listener records history; retention purge task"
```

---

### Task 7: Stats service (tiles + throughput buckets)

**Files:**
- Create: `console-core/src/main/java/io/github/logicsatinn/dbscheduler/console/service/StatsService.java`
- Test: `console-core/src/test/java/io/github/logicsatinn/dbscheduler/console/service/StatsServiceTest.java`

**Interfaces:**
- Consumes: `ExecutionRepository.counts(Instant)` / `LiveCounts`, `HistoryRepository.countsSince/window/tableExists` (Tasks 3–4).
- Produces:
  - `class StatsService` — ctor `(ExecutionRepository, HistoryRepository, Clock)`
  - `record Tiles(long scheduled, long due, long running, long failing, long succeeded24h, long failed24h)` (nested)
  - `record HourBucket(Instant hourStart, long succeeded, long failed)` (nested)
  - `Tiles tiles()`
  - `List<HourBucket> throughputLast24h()` — exactly 24 buckets oldest→newest, or an **empty list** when the history table is missing
  - `boolean historyAvailable()` — true once the table exists (caches `true`, re-checks while `false`)

- [ ] **Step 1: Write the failing test**

```java
package io.github.logicsatinn.dbscheduler.console.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.logicsatinn.dbscheduler.console.data.Dialect;
import io.github.logicsatinn.dbscheduler.console.data.ExecutionRepository;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryEntry;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

class StatsServiceTest {

    static final Instant NOW = Instant.parse("2026-07-13T12:30:00Z");

    DataSource ds;
    JdbcTemplate jdbc;
    StatsService stats;
    HistoryRepository history;

    @BeforeEach
    void setUp() {
        ds = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .addScript("db-scheduler-schema/h2.sql")
                .addScript("db-scheduler-console/migrations/h2.sql")
                .build();
        jdbc = new JdbcTemplate(ds);
        history = new HistoryRepository(ds, Dialect.H2);
        stats = new StatsService(
                new ExecutionRepository(ds, "scheduled_tasks", Dialect.H2),
                history,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    HistoryEntry at(Instant started, HistoryEntry.Outcome outcome) {
        return new HistoryEntry(0, "t", "i", outcome, started, started.plusSeconds(1), 1000,
                null, null, null, "n");
    }

    @Test
    void tilesCombineLiveAndHistoryCounts() {
        jdbc.update("insert into scheduled_tasks (task_name, task_instance, execution_time, picked, version)"
                + " values ('a','1', ?, false, 1)", Timestamp.from(NOW.plusSeconds(600)));
        jdbc.update("insert into scheduled_tasks (task_name, task_instance, execution_time, picked, picked_by, version)"
                + " values ('a','2', ?, true, 'n1', 1)", Timestamp.from(NOW));
        history.insert(at(NOW.minusSeconds(60), HistoryEntry.Outcome.SUCCEEDED));
        history.insert(at(NOW.minusSeconds(120), HistoryEntry.Outcome.FAILED));
        history.insert(at(NOW.minus(3, ChronoUnit.DAYS), HistoryEntry.Outcome.FAILED)); // outside 24h

        var tiles = stats.tiles();
        assertThat(tiles.scheduled()).isEqualTo(1);
        assertThat(tiles.running()).isEqualTo(1);
        assertThat(tiles.succeeded24h()).isEqualTo(1);
        assertThat(tiles.failed24h()).isEqualTo(1);
    }

    @Test
    void throughputBucketsAreHourAlignedOldestFirst() {
        // NOW is 12:30 → window is [13:00 yesterday, 13:00 today), buckets aligned to hours
        history.insert(at(NOW.minusSeconds(300), HistoryEntry.Outcome.SUCCEEDED)); // 12:25 → last bucket
        history.insert(at(NOW.minusSeconds(300), HistoryEntry.Outcome.FAILED));
        history.insert(at(NOW.minus(5, ChronoUnit.HOURS), HistoryEntry.Outcome.SUCCEEDED));
        history.insert(at(NOW.minus(30, ChronoUnit.HOURS), HistoryEntry.Outcome.SUCCEEDED)); // outside

        var buckets = stats.throughputLast24h();
        assertThat(buckets).hasSize(24);
        assertThat(buckets.get(23).hourStart()).isEqualTo(Instant.parse("2026-07-13T12:00:00Z"));
        assertThat(buckets.get(23).succeeded()).isEqualTo(1);
        assertThat(buckets.get(23).failed()).isEqualTo(1);
        assertThat(buckets.get(18).succeeded()).isEqualTo(1); // 07:00–08:00 bucket
        long totalCounted = buckets.stream().mapToLong(b -> b.succeeded() + b.failed()).sum();
        assertThat(totalCounted).isEqualTo(3);
    }

    @Test
    void degradesWhenHistoryTableMissing() {
        DataSource bare = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .addScript("db-scheduler-schema/h2.sql")
                .build();
        var s = new StatsService(
                new ExecutionRepository(bare, "scheduled_tasks", Dialect.H2),
                new HistoryRepository(bare, Dialect.H2),
                Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(s.historyAvailable()).isFalse();
        assertThat(s.tiles().succeeded24h()).isZero();
        assertThat(s.throughputLast24h()).isEmpty();
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :console-core:test --tests '*StatsServiceTest'`
Expected: FAIL — `StatsService` does not exist.

- [ ] **Step 3: Implement `StatsService`**

```java
package io.github.logicsatinn.dbscheduler.console.service;

import io.github.logicsatinn.dbscheduler.console.data.ExecutionRepository;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryEntry;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

public class StatsService {

    private static final int WINDOW_CAP = 200_000;

    private final ExecutionRepository executions;
    private final HistoryRepository history;
    private final Clock clock;
    private volatile boolean historyTableSeen;

    public StatsService(ExecutionRepository executions, HistoryRepository history, Clock clock) {
        this.executions = executions;
        this.history = history;
        this.clock = clock;
    }

    public record Tiles(long scheduled, long due, long running, long failing,
                        long succeeded24h, long failed24h) {}

    public record HourBucket(Instant hourStart, long succeeded, long failed) {}

    public boolean historyAvailable() {
        if (!historyTableSeen) {
            historyTableSeen = history.tableExists();
        }
        return historyTableSeen;
    }

    public Tiles tiles() {
        Instant now = clock.instant();
        var live = executions.counts(now);
        var outcomes = historyAvailable()
                ? history.countsSince(now.minus(24, ChronoUnit.HOURS))
                : new HistoryRepository.OutcomeCounts(0, 0);
        return new Tiles(live.scheduled(), live.due(), live.running(), live.failing(),
                outcomes.succeeded(), outcomes.failed());
    }

    /** 24 hour-aligned buckets, oldest first; empty list if history is unavailable. */
    public List<HourBucket> throughputLast24h() {
        if (!historyAvailable()) {
            return List.of();
        }
        Instant end = clock.instant().truncatedTo(ChronoUnit.HOURS).plus(1, ChronoUnit.HOURS);
        Instant start = end.minus(24, ChronoUnit.HOURS);
        long[] succeeded = new long[24];
        long[] failed = new long[24];
        for (var p : history.window(start, end, WINDOW_CAP)) {
            int idx = (int) ChronoUnit.HOURS.between(start, p.startedAt());
            if (idx < 0 || idx > 23) {
                continue;
            }
            if (p.outcome() == HistoryEntry.Outcome.SUCCEEDED) {
                succeeded[idx]++;
            } else {
                failed[idx]++;
            }
        }
        List<HourBucket> buckets = new ArrayList<>(24);
        for (int i = 0; i < 24; i++) {
            buckets.add(new HourBucket(start.plus(i, ChronoUnit.HOURS), succeeded[i], failed[i]));
        }
        return buckets;
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :console-core:test --tests '*StatsServiceTest'`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add console-core
git commit -m "feat(core): stats service for tiles and hourly throughput buckets"
```

---

### Task 8: Execution actions via SchedulerClient

**Files:**
- Create: `console-core/src/main/java/io/github/logicsatinn/dbscheduler/console/service/ExecutionActions.java`
- Test: `console-core/src/test/java/io/github/logicsatinn/dbscheduler/console/service/ExecutionActionsTest.java`

**Interfaces:**
- Consumes: db-scheduler `SchedulerClient` (`boolean reschedule(TaskInstanceId, Instant)`, `void cancel(TaskInstanceId)`), `TaskInstanceId.of(String, String)`.
- Produces:
  - `class ExecutionActions` — ctor `(SchedulerClient, Clock)`
  - `record ActionResult(boolean success, String message)` (nested)
  - `record InstanceRef(String taskName, String instanceId)` (nested) with `String composite()` / `static InstanceRef parse(String composite)` for the checkbox form value `taskName + "::" + instanceId` (split on the first `::`; task names must not contain `::` — instance ids may)
  - `enum Kind { RUN_NOW, DELETE }` (nested)
  - `ActionResult runNow(String taskName, String instanceId)`
  - `ActionResult reschedule(String taskName, String instanceId, Instant when)`
  - `ActionResult delete(String taskName, String instanceId)`
  - `ActionResult bulk(Kind kind, List<InstanceRef> refs)` — aggregate message like `"Run now: 2 succeeded, 1 failed"`

- [ ] **Step 1: Write the failing test**

```java
package io.github.logicsatinn.dbscheduler.console.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import io.github.logicsatinn.dbscheduler.console.data.Dialect;
import io.github.logicsatinn.dbscheduler.console.data.ExecutionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

class ExecutionActionsTest {

    static final Instant NOW = Instant.parse("2026-07-13T12:00:00Z");
    static final OneTimeTask<Void> TASK = Tasks.oneTime("action-task").execute((i, c) -> {});

    DataSource ds;
    JdbcTemplate jdbc;
    SchedulerClient client;
    ExecutionActions actions;
    ExecutionRepository repo;

    @BeforeEach
    void setUp() {
        ds = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .addScript("db-scheduler-schema/h2.sql")
                .build();
        jdbc = new JdbcTemplate(ds);
        client = SchedulerClient.Builder.create(ds, TASK).build();
        actions = new ExecutionActions(client, Clock.fixed(NOW, ZoneOffset.UTC));
        repo = new ExecutionRepository(ds, "scheduled_tasks", Dialect.H2);
    }

    void scheduleAt(String id, Instant when) {
        client.scheduleIfNotExists(TASK.instance(id), when);
    }

    @Test
    void runNowMovesExecutionTime() {
        scheduleAt("1", NOW.plus(2, ChronoUnit.HOURS));
        var result = actions.runNow("action-task", "1");
        assertThat(result.success()).isTrue();
        assertThat(repo.find("action-task", "1").orElseThrow().executionTime()).isEqualTo(NOW);
    }

    @Test
    void rescheduleSetsExactTime() {
        scheduleAt("1", NOW.plus(2, ChronoUnit.HOURS));
        Instant target = NOW.plus(1, ChronoUnit.DAYS);
        var result = actions.reschedule("action-task", "1", target);
        assertThat(result.success()).isTrue();
        assertThat(repo.find("action-task", "1").orElseThrow().executionTime()).isEqualTo(target);
    }

    @Test
    void deleteCancelsExecution() {
        scheduleAt("1", NOW.plus(2, ChronoUnit.HOURS));
        var result = actions.delete("action-task", "1");
        assertThat(result.success()).isTrue();
        assertThat(repo.find("action-task", "1")).isEmpty();
    }

    @Test
    void actionsOnPickedOrMissingExecutionsFailGracefully() {
        scheduleAt("picked", NOW.plus(1, ChronoUnit.HOURS));
        jdbc.update("update scheduled_tasks set picked = true, picked_by = 'node-1'"
                + " where task_instance = 'picked'");

        assertThat(actions.runNow("action-task", "picked").success()).isFalse();
        assertThat(actions.delete("action-task", "picked").success()).isFalse();
        assertThat(actions.runNow("action-task", "missing").success()).isFalse();
    }

    @Test
    void bulkAggregates() {
        scheduleAt("a", NOW.plus(1, ChronoUnit.HOURS));
        scheduleAt("b", NOW.plus(1, ChronoUnit.HOURS));
        var result = actions.bulk(ExecutionActions.Kind.RUN_NOW, List.of(
                new ExecutionActions.InstanceRef("action-task", "a"),
                new ExecutionActions.InstanceRef("action-task", "b"),
                new ExecutionActions.InstanceRef("action-task", "missing")));
        assertThat(result.success()).isFalse(); // at least one failure
        assertThat(result.message()).contains("2 succeeded").contains("1 failed");
    }

    @Test
    void instanceRefRoundTrip() {
        var ref = new ExecutionActions.InstanceRef("t", "i");
        assertThat(ExecutionActions.InstanceRef.parse(ref.composite())).isEqualTo(ref);
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :console-core:test --tests '*ExecutionActionsTest'`
Expected: FAIL — `ExecutionActions` does not exist.

- [ ] **Step 3: Implement `ExecutionActions`**

```java
package io.github.logicsatinn.dbscheduler.console.service;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.TaskInstanceId;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/** All mutations go through SchedulerClient so optimistic locking is respected. */
public class ExecutionActions {

    private final SchedulerClient client;
    private final Clock clock;

    public ExecutionActions(SchedulerClient client, Clock clock) {
        this.client = client;
        this.clock = clock;
    }

    public record ActionResult(boolean success, String message) {}

    public enum Kind { RUN_NOW, DELETE }

    public record InstanceRef(String taskName, String instanceId) {
        private static final String SEP = "::";

        public String composite() {
            return taskName + SEP + instanceId;
        }

        public static InstanceRef parse(String composite) {
            int i = composite.indexOf(SEP);
            if (i < 0) {
                throw new IllegalArgumentException("Malformed instance ref");
            }
            return new InstanceRef(composite.substring(0, i), composite.substring(i + 1));
        }
    }

    public ActionResult runNow(String taskName, String instanceId) {
        return rescheduleTo(taskName, instanceId, clock.instant(), "Run now");
    }

    public ActionResult reschedule(String taskName, String instanceId, Instant when) {
        return rescheduleTo(taskName, instanceId, when, "Reschedule");
    }

    private ActionResult rescheduleTo(String taskName, String instanceId, Instant when, String verb) {
        try {
            boolean ok = client.reschedule(TaskInstanceId.of(taskName, instanceId), when);
            return ok
                    ? new ActionResult(true, verb + " succeeded for " + instanceId)
                    : new ActionResult(false, verb + " failed for " + instanceId
                            + " — it is executing right now, was changed concurrently, or no longer exists");
        } catch (RuntimeException e) {
            return new ActionResult(false, verb + " failed for " + instanceId + ": " + e.getMessage());
        }
    }

    public ActionResult delete(String taskName, String instanceId) {
        try {
            client.cancel(TaskInstanceId.of(taskName, instanceId));
            return new ActionResult(true, "Deleted " + instanceId);
        } catch (RuntimeException e) {
            return new ActionResult(false, "Delete failed for " + instanceId
                    + " — it is executing right now or no longer exists");
        }
    }

    public ActionResult bulk(Kind kind, List<InstanceRef> refs) {
        int succeeded = 0;
        int failed = 0;
        for (InstanceRef ref : refs) {
            ActionResult r = switch (kind) {
                case RUN_NOW -> runNow(ref.taskName(), ref.instanceId());
                case DELETE -> delete(ref.taskName(), ref.instanceId());
            };
            if (r.success()) succeeded++; else failed++;
        }
        String label = switch (kind) {
            case RUN_NOW -> "Run now";
            case DELETE -> "Delete";
        };
        return new ActionResult(failed == 0,
                label + ": " + succeeded + " succeeded, " + failed + " failed");
    }
}
```

Note: if `runNow` on a missing execution does not throw and `reschedule` returns `true` unexpectedly on H2, check db-scheduler's return semantics in the failing test output — the contract asserted here (missing/picked ⇒ `success() == false`) is what matters; adjust only the message text, never the contract.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :console-core:test --tests '*ExecutionActionsTest'`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add console-core
git commit -m "feat(core): execution actions (run-now, reschedule, delete, bulk) via SchedulerClient"
```

---

### Task 9: Task data renderer

**Files:**
- Create: `console-core/src/main/java/io/github/logicsatinn/dbscheduler/console/service/TaskDataRenderer.java`
- Test: `console-core/src/test/java/io/github/logicsatinn/dbscheduler/console/service/TaskDataRendererTest.java`

**Interfaces:**
- Consumes: db-scheduler `Serializer` (`<T> T deserialize(Class<T> clazz, byte[] serializedData)`), nullable.
- Produces: `class TaskDataRenderer` — ctor `(Serializer serializerOrNull, boolean visible)`; method `String render(byte[] taskData)`. Output rules: hidden → `"(hidden)"`; null/empty → `"—"`; serializer succeeds → `String.valueOf(result)`; printable UTF-8 → the text; otherwise `"binary (N bytes)"`. Output capped at 10000 chars with a `"… (truncated)"` suffix.

- [ ] **Step 1: Write the failing test**

```java
package io.github.logicsatinn.dbscheduler.console.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.kagkarlsson.scheduler.serializer.Serializer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TaskDataRendererTest {

    @Test
    void hiddenModeShowsNothing() {
        var r = new TaskDataRenderer(null, false);
        assertThat(r.render("{\"a\":1}".getBytes(StandardCharsets.UTF_8))).isEqualTo("(hidden)");
    }

    @Test
    void nullAndEmptyShowDash() {
        var r = new TaskDataRenderer(null, true);
        assertThat(r.render(null)).isEqualTo("—");
        assertThat(r.render(new byte[0])).isEqualTo("—");
    }

    @Test
    void printableUtf8IsShownAsText() {
        var r = new TaskDataRenderer(null, true);
        assertThat(r.render("{\"orderId\":42}".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("{\"orderId\":42}");
    }

    @Test
    void binaryFallsBackToByteCount() {
        var r = new TaskDataRenderer(null, true);
        byte[] javaSerialized = {(byte) 0xAC, (byte) 0xED, 0x00, 0x05, 0x01, 0x02};
        assertThat(r.render(javaSerialized)).isEqualTo("binary (6 bytes)");
    }

    @Test
    void serializerWinsWhenItWorks() {
        Serializer serializer = new Serializer() {
            @Override public byte[] serialize(Object data) { return new byte[0]; }
            @Override @SuppressWarnings("unchecked")
            public <T> T deserialize(Class<T> clazz, byte[] data) { return (T) "MyData[id=7]"; }
        };
        var r = new TaskDataRenderer(serializer, true);
        assertThat(r.render(new byte[] {1})).isEqualTo("MyData[id=7]");
    }

    @Test
    void throwingSerializerFallsThrough() {
        Serializer serializer = new Serializer() {
            @Override public byte[] serialize(Object data) { return new byte[0]; }
            @Override public <T> T deserialize(Class<T> clazz, byte[] data) {
                throw new IllegalStateException("unknown class");
            }
        };
        var r = new TaskDataRenderer(serializer, true);
        assertThat(r.render("plain text".getBytes(StandardCharsets.UTF_8))).isEqualTo("plain text");
    }

    @Test
    void longOutputIsCapped() {
        var r = new TaskDataRenderer(null, true);
        String result = r.render("x".repeat(20000).getBytes(StandardCharsets.UTF_8));
        assertThat(result).hasSize(10000 + "… (truncated)".length()).endsWith("… (truncated)");
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :console-core:test --tests '*TaskDataRendererTest'`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Implement `TaskDataRenderer`**

```java
package io.github.logicsatinn.dbscheduler.console.service;

import com.github.kagkarlsson.scheduler.serializer.Serializer;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Renders task_data bytes for humans without pretending to decode what it cannot. */
public class TaskDataRenderer {

    static final int MAX_CHARS = 10000;

    private final Serializer serializer;
    private final boolean visible;

    public TaskDataRenderer(Serializer serializer, boolean visible) {
        this.serializer = serializer;
        this.visible = visible;
    }

    public String render(byte[] taskData) {
        if (!visible) {
            return "(hidden)";
        }
        if (taskData == null || taskData.length == 0) {
            return "—";
        }
        if (serializer != null) {
            try {
                Object o = serializer.deserialize(Object.class, taskData);
                if (o != null) {
                    return cap(String.valueOf(o));
                }
            } catch (RuntimeException ignored) {
                // fall through to the UTF-8 heuristic
            }
        }
        String text = tryPrintableUtf8(taskData);
        if (text != null) {
            return cap(text);
        }
        return "binary (" + taskData.length + " bytes)";
    }

    private static String tryPrintableUtf8(byte[] data) {
        try {
            String decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data))
                    .toString();
            boolean printable = decoded.chars()
                    .allMatch(c -> c >= 32 || c == '\n' || c == '\r' || c == '\t');
            return printable ? decoded : null;
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static String cap(String s) {
        return s.length() <= MAX_CHARS ? s : s.substring(0, MAX_CHARS) + "… (truncated)";
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :console-core:test --tests '*TaskDataRendererTest'`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add console-core
git commit -m "feat(core): serializer-aware task data rendering with binary fallback"
```

---

### Task 10: Web infrastructure (JTE, layout, assets, properties)

**Files:**
- Modify: `console-core/build.gradle.kts` (add JTE plugin)
- Create: `console-core/src/main/java/io/github/logicsatinn/dbscheduler/console/ConsoleProperties.java`
- Create: `…/console/web/TemplateRenderer.java`, `PageCtx.java`, `PageCtxFactory.java`, `CsrfSupport.java`, `Fmt.java`, `StaticAssetsController.java`, `OverviewController.java` (skeleton)
- Create: `console-core/src/main/jte/layout.jte`, `console-core/src/main/jte/pages/overview.jte` (skeleton)
- Create: `console-core/src/main/resources/db-scheduler-console/static/console.css`, `htmx.min.js` (downloaded once, committed)
- Test: `console-core/src/test/java/io/github/logicsatinn/dbscheduler/console/web/WebInfraTest.java`, `FmtTest.java`

**Interfaces:**
- Consumes: `StatsService` (Task 7).
- Produces:
  - `class ConsoleProperties` (`@ConfigurationProperties(prefix = "db-scheduler-console")`): `boolean enabled=true`, `String basePath="/db-scheduler-console"`, `boolean readOnly=false`, `Duration pollingInterval=5s`, nested `History { boolean enabled=true; Duration retention=14d }`, nested `TaskData { boolean visible=true }` — standard getters/setters (`isEnabled`, `getBasePath`, …, `getHistory().getRetention()`, `getTaskData().isVisible()`).
  - `class TemplateRenderer`: `String render(String template, Map<String, Object> params)`; `ResponseEntity<String> page(String template, Map<String, Object> params)` (adds `Content-Type: text/html;charset=UTF-8`).
  - `record PageCtx(String basePath, boolean readOnly, long pollSeconds, String csrfHeaderJson, String active)`.
  - `class PageCtxFactory`: ctor `(ConsoleProperties)`; `PageCtx page(String active, HttpServletRequest request)`.
  - `final class CsrfSupport`: `static String headerJson(HttpServletRequest request)` — hx-headers JSON when Spring Security CSRF is active, else null. Never fails when spring-security-web is absent from the classpath.
  - `final class Fmt`: `static String ts(Instant i)` (`yyyy-MM-dd HH:mm:ss` system zone, `"—"` for null); `static String duration(long ms)` (`"450 ms"`, `"2.3 s"`, `"1 m 12 s"`).
  - All controllers map under `@RequestMapping("${db-scheduler-console.base-path:/db-scheduler-console}")`.
  - JTE templates render via `TemplateEngine.createPrecompiled(ContentType.Html)` — the Gradle plugin's `generate()` mode compiles templates into the jar; no runtime engine.

- [ ] **Step 1: Add the JTE plugin to `console-core/build.gradle.kts`**

Change the plugins block to:
```kotlin
plugins {
    `java-library`
    alias(libs.plugins.jte)
}
```
and add after the `java {}` block:
```kotlin
jte {
    sourceDirectory.set(file("src/main/jte").toPath())
    contentType.set(gg.jte.ContentType.Html)
    generate()
}
```

- [ ] **Step 2: Vendor htmx 2.0.10 (one-time download, committed to git)**

Run:
```bash
mkdir -p console-core/src/main/resources/db-scheduler-console/static
curl -fsSL https://unpkg.com/htmx.org@2.0.10/dist/htmx.min.js \
  -o console-core/src/main/resources/db-scheduler-console/static/htmx.min.js
grep -o '2\.0\.10' console-core/src/main/resources/db-scheduler-console/static/htmx.min.js | head -1
```
Expected: `2.0.10` printed (~51 KB file).

- [ ] **Step 3: Write `console.css`** (design tokens are the validated reference palette — do not improvise colors)

```css
:root {
  --surface: #fcfcfb;
  --page: #f9f9f7;
  --ink: #0b0b0b;
  --ink-2: #52514e;
  --muted: #898781;
  --grid: #e1e0d9;
  --axis: #c3c2b7;
  --border: rgba(11, 11, 11, 0.10);
  --accent: #2a78d6;
  --status-good: #0ca30c;
  --status-warning: #fab219;
  --status-serious: #ec835a;
  --status-critical: #d03b3b;
}
@media (prefers-color-scheme: dark) {
  :root {
    --surface: #1a1a19;
    --page: #0d0d0d;
    --ink: #ffffff;
    --ink-2: #c3c2b7;
    --grid: #2c2c2a;
    --axis: #383835;
    --border: rgba(255, 255, 255, 0.10);
    --accent: #3987e5;
  }
}
* { box-sizing: border-box; }
body {
  margin: 0;
  background: var(--page);
  color: var(--ink);
  font: 14px/1.5 system-ui, -apple-system, "Segoe UI", sans-serif;
}
a { color: var(--accent); }
.muted { color: var(--muted); }
.shell { max-width: 1200px; margin: 0 auto; padding: 0 20px 40px; }
.nav {
  display: flex; gap: 12px; align-items: center;
  padding: 14px 0; border-bottom: 1px solid var(--grid); margin-bottom: 24px;
}
.nav .brand { font-weight: 600; margin-right: 8px; }
.nav a { color: var(--ink-2); text-decoration: none; padding: 4px 10px; border-radius: 6px; }
.nav a:hover { color: var(--ink); }
.nav a.active { color: var(--ink); background: var(--surface); border: 1px solid var(--border); }
.badge {
  font-size: 11px; text-transform: uppercase; letter-spacing: 0.05em;
  color: var(--status-critical); border: 1px solid currentColor;
  padding: 1px 8px; border-radius: 999px;
}
h1 { font-size: 20px; margin: 0 0 16px; }
.tiles {
  display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 12px; margin-bottom: 24px;
}
.tile { background: var(--surface); border: 1px solid var(--border); border-radius: 10px; padding: 14px 16px; }
.tile .label { font-size: 11px; text-transform: uppercase; letter-spacing: 0.06em; color: var(--ink-2); }
.tile .value { font-size: 28px; font-weight: 600; margin-top: 2px; }
.tile.alert .value { color: var(--status-critical); }
.tile.alert .value::before { content: "⚠ "; font-size: 18px; }
.card { background: var(--surface); border: 1px solid var(--border); border-radius: 10px; padding: 16px; margin-bottom: 24px; }
.card h2 { font-size: 14px; margin: 0 0 12px; color: var(--ink-2); font-weight: 600; }
table.data { width: 100%; border-collapse: collapse; }
table.data th {
  text-align: left; font-size: 11px; text-transform: uppercase; letter-spacing: 0.05em;
  color: var(--muted); padding: 8px; border-bottom: 1px solid var(--grid); white-space: nowrap;
}
table.data th a { color: inherit; text-decoration: none; }
table.data td { padding: 8px; border-bottom: 1px solid var(--grid); vertical-align: top; }
table.data td.num, table.data td.time { font-variant-numeric: tabular-nums; white-space: nowrap; }
.state { font-size: 12px; padding: 1px 8px; border-radius: 999px; border: 1px solid var(--axis); color: var(--ink-2); white-space: nowrap; }
.state.running { border-color: var(--accent); color: var(--accent); }
.state.failing, .state.failed { border-color: var(--status-critical); color: var(--status-critical); }
.state.succeeded { border-color: var(--status-good); color: var(--status-good); }
.filters { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 16px; align-items: flex-end; }
.filters label { display: flex; flex-direction: column; gap: 4px; font-size: 11px; color: var(--ink-2); }
input, select, button {
  font: inherit; color: var(--ink); background: var(--surface);
  border: 1px solid var(--axis); border-radius: 6px; padding: 6px 10px;
}
button { cursor: pointer; }
button:hover { border-color: var(--accent); }
button.danger:hover { border-color: var(--status-critical); color: var(--status-critical); }
.toolbar { display: flex; gap: 8px; margin: 12px 0; align-items: center; }
.legend { display: flex; gap: 16px; font-size: 12px; color: var(--ink-2); margin-top: 8px; }
.legend .key { display: inline-block; width: 10px; height: 10px; border-radius: 3px; margin-right: 6px; vertical-align: -1px; }
.legend .key.good { background: var(--status-good); }
.legend .key.critical { background: var(--status-critical); }
.chart-svg { width: 100%; height: auto; display: block; }
.viz-good { fill: var(--status-good); }
.viz-critical { fill: var(--status-critical); }
.chart-grid { stroke: var(--grid); stroke-width: 1; }
.chart-label { fill: var(--muted); font-size: 10px; }
.flash-area { position: fixed; right: 20px; bottom: 20px; display: flex; flex-direction: column; gap: 8px; z-index: 10; }
.flash {
  background: var(--surface); border: 1px solid var(--border);
  border-left: 4px solid var(--status-good); border-radius: 8px;
  padding: 10px 14px; box-shadow: 0 4px 12px rgba(0, 0, 0, 0.12);
}
.flash.error { border-left-color: var(--status-critical); }
.pagination { display: flex; gap: 8px; align-items: center; margin-top: 12px; font-variant-numeric: tabular-nums; }
details.stack summary { cursor: pointer; color: var(--ink-2); }
details.stack pre {
  background: var(--page); border: 1px solid var(--grid); border-radius: 6px;
  padding: 10px; overflow-x: auto; font-size: 12px;
}
pre.data { background: var(--page); border: 1px solid var(--grid); border-radius: 6px; padding: 10px; overflow-x: auto; }
.empty { color: var(--muted); padding: 24px; text-align: center; }
```

- [ ] **Step 4: Write the failing tests**

`FmtTest.java`:
```java
package io.github.logicsatinn.dbscheduler.console.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FmtTest {

    @Test
    void formatsDurations() {
        assertThat(Fmt.duration(450)).isEqualTo("450 ms");
        assertThat(Fmt.duration(2300)).isEqualTo("2.3 s");
        assertThat(Fmt.duration(72000)).isEqualTo("1 m 12 s");
    }

    @Test
    void nullInstantIsDash() {
        assertThat(Fmt.ts(null)).isEqualTo("—");
    }
}
```

`WebInfraTest.java`:
```java
package io.github.logicsatinn.dbscheduler.console.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.logicsatinn.dbscheduler.console.ConsoleProperties;
import io.github.logicsatinn.dbscheduler.console.data.Dialect;
import io.github.logicsatinn.dbscheduler.console.data.ExecutionRepository;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryRepository;
import io.github.logicsatinn.dbscheduler.console.service.StatsService;
import java.time.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WebInfraTest {

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        var ds = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .addScript("db-scheduler-schema/h2.sql")
                .addScript("db-scheduler-console/migrations/h2.sql")
                .build();
        var props = new ConsoleProperties();
        var history = new HistoryRepository(ds, Dialect.H2);
        var stats = new StatsService(
                new ExecutionRepository(ds, "scheduled_tasks", Dialect.H2), history, Clock.systemUTC());
        var overview = new OverviewController(
                new PageCtxFactory(props), new TemplateRenderer(), stats, history);
        mvc = MockMvcBuilders.standaloneSetup(overview, new StaticAssetsController())
                .addPlaceholderValue("db-scheduler-console.base-path", "/db-scheduler-console")
                .build();
    }

    @Test
    void overviewRendersLayout() throws Exception {
        var body = mvc.perform(get("/db-scheduler-console/overview"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andReturn().getResponse().getContentAsString();
        var doc = org.jsoup.Jsoup.parse(body);
        assertThat(doc.select("nav .brand").text()).isEqualTo("db-scheduler-console");
        assertThat(doc.select("nav a.active").text()).isEqualTo("Overview");
        assertThat(doc.select("script[src$=htmx.min.js]")).hasSize(1);
        assertThat(doc.select("link[href$=console.css]")).hasSize(1);
    }

    @Test
    void rootRedirectsToOverviewContent() throws Exception {
        mvc.perform(get("/db-scheduler-console")).andExpect(status().isOk());
    }

    @Test
    void servesStaticAssets() throws Exception {
        var css = mvc.perform(get("/db-scheduler-console/static/console.css"))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        assertThat(css.getContentType()).startsWith("text/css");
        assertThat(css.getContentAsString()).contains("--status-good");

        var js = mvc.perform(get("/db-scheduler-console/static/htmx.min.js"))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        assertThat(js.getContentAsString()).contains("htmx");

        mvc.perform(get("/db-scheduler-console/static/nope.js"))
                .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 5: Run to verify failure**

Run: `./gradlew :console-core:test --tests '*WebInfraTest' --tests '*FmtTest'`
Expected: FAIL — classes do not exist.

- [ ] **Step 6: Implement `ConsoleProperties`**

```java
package io.github.logicsatinn.dbscheduler.console;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "db-scheduler-console")
public class ConsoleProperties {

    private boolean enabled = true;
    private String basePath = "/db-scheduler-console";
    private boolean readOnly = false;
    private Duration pollingInterval = Duration.ofSeconds(5);
    private final History history = new History();
    private final TaskData taskData = new TaskData();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBasePath() { return basePath; }
    public void setBasePath(String basePath) { this.basePath = basePath; }
    public boolean isReadOnly() { return readOnly; }
    public void setReadOnly(boolean readOnly) { this.readOnly = readOnly; }
    public Duration getPollingInterval() { return pollingInterval; }
    public void setPollingInterval(Duration pollingInterval) { this.pollingInterval = pollingInterval; }
    public History getHistory() { return history; }
    public TaskData getTaskData() { return taskData; }

    public static class History {
        private boolean enabled = true;
        private Duration retention = Duration.ofDays(14);

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public Duration getRetention() { return retention; }
        public void setRetention(Duration retention) { this.retention = retention; }
    }

    public static class TaskData {
        private boolean visible = true;

        public boolean isVisible() { return visible; }
        public void setVisible(boolean visible) { this.visible = visible; }
    }
}
```

- [ ] **Step 7: Implement the web support classes**

`TemplateRenderer.java`:
```java
package io.github.logicsatinn.dbscheduler.console.web;

import gg.jte.ContentType;
import gg.jte.TemplateEngine;
import gg.jte.output.StringOutput;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

public class TemplateRenderer {

    private final TemplateEngine engine = TemplateEngine.createPrecompiled(ContentType.Html);

    public String render(String template, Map<String, Object> params) {
        StringOutput out = new StringOutput();
        engine.render(template, params, out);
        return out.toString();
    }

    public ResponseEntity<String> page(String template, Map<String, Object> params) {
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("text/html;charset=UTF-8"))
                .body(render(template, params));
    }
}
```

`PageCtx.java`:
```java
package io.github.logicsatinn.dbscheduler.console.web;

public record PageCtx(String basePath, boolean readOnly, long pollSeconds,
                      String csrfHeaderJson, String active) {}
```

`PageCtxFactory.java`:
```java
package io.github.logicsatinn.dbscheduler.console.web;

import io.github.logicsatinn.dbscheduler.console.ConsoleProperties;
import jakarta.servlet.http.HttpServletRequest;

public class PageCtxFactory {

    private final ConsoleProperties props;

    public PageCtxFactory(ConsoleProperties props) {
        this.props = props;
    }

    public PageCtx page(String active, HttpServletRequest request) {
        return new PageCtx(
                props.getBasePath(),
                props.isReadOnly(),
                Math.max(1, props.getPollingInterval().toSeconds()),
                CsrfSupport.headerJson(request),
                active);
    }
}
```

`CsrfSupport.java`:
```java
package io.github.logicsatinn.dbscheduler.console.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.ClassUtils;

/** Emits htmx hx-headers JSON when Spring Security CSRF is active. Safe without spring-security-web. */
public final class CsrfSupport {

    private static final boolean SPRING_SECURITY_PRESENT = ClassUtils.isPresent(
            "org.springframework.security.web.csrf.CsrfToken", CsrfSupport.class.getClassLoader());

    private CsrfSupport() {}

    public static String headerJson(HttpServletRequest request) {
        if (!SPRING_SECURITY_PRESENT || request == null) {
            return null;
        }
        return Holder.json(request);
    }

    /** Separate class so CsrfToken is only loaded when spring-security-web is on the classpath. */
    private static final class Holder {
        static String json(HttpServletRequest request) {
            Object attr = request.getAttribute(
                    org.springframework.security.web.csrf.CsrfToken.class.getName());
            if (attr instanceof org.springframework.security.web.csrf.CsrfToken token
                    && token.getToken() != null) {
                return "{\"" + token.getHeaderName() + "\":\"" + token.getToken() + "\"}";
            }
            return null;
        }
    }
}
```

`Fmt.java`:
```java
package io.github.logicsatinn.dbscheduler.console.web;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class Fmt {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private Fmt() {}

    public static String ts(Instant i) {
        return i == null ? "—" : TS.format(i);
    }

    public static String duration(long ms) {
        if (ms < 1000) {
            return ms + " ms";
        }
        if (ms < 60_000) {
            return String.format(Locale.ROOT, "%.1f s", ms / 1000.0);
        }
        return (ms / 60_000) + " m " + (ms % 60_000) / 1000 + " s";
    }
}
```

`StaticAssetsController.java`:
```java
package io.github.logicsatinn.dbscheduler.console.web;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("${db-scheduler-console.base-path:/db-scheduler-console}")
public class StaticAssetsController {

    @GetMapping("/static/{file:.+}")
    public ResponseEntity<byte[]> asset(@PathVariable String file) throws IOException {
        if (file.contains("..") || file.contains("/")) {
            return ResponseEntity.notFound().build();
        }
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("db-scheduler-console/static/" + file)) {
            if (in == null) {
                return ResponseEntity.notFound().build();
            }
            MediaType type = file.endsWith(".css") ? MediaType.valueOf("text/css")
                    : file.endsWith(".js") ? MediaType.valueOf("text/javascript")
                    : MediaType.APPLICATION_OCTET_STREAM;
            return ResponseEntity.ok()
                    .contentType(type)
                    .cacheControl(CacheControl.maxAge(Duration.ofHours(1)))
                    .body(in.readAllBytes());
        }
    }
}
```

`OverviewController.java` (skeleton — extended in Task 11):
```java
package io.github.logicsatinn.dbscheduler.console.web;

import io.github.logicsatinn.dbscheduler.console.data.history.HistoryRepository;
import io.github.logicsatinn.dbscheduler.console.service.StatsService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("${db-scheduler-console.base-path:/db-scheduler-console}")
public class OverviewController {

    private final PageCtxFactory ctxFactory;
    private final TemplateRenderer templates;
    private final StatsService stats;
    private final HistoryRepository history;

    public OverviewController(PageCtxFactory ctxFactory, TemplateRenderer templates,
            StatsService stats, HistoryRepository history) {
        this.ctxFactory = ctxFactory;
        this.templates = templates;
        this.stats = stats;
        this.history = history;
    }

    @GetMapping({"", "/", "/overview"})
    public ResponseEntity<String> overview(HttpServletRequest request) {
        return templates.page("pages/overview.jte", Map.of(
                "ctx", ctxFactory.page("overview", request),
                "tiles", stats.tiles(),
                "historyAvailable", stats.historyAvailable()));
    }
}
```

- [ ] **Step 8: Write the templates**

`layout.jte`:
```html
@param io.github.logicsatinn.dbscheduler.console.web.PageCtx ctx
@param String title
@param gg.jte.Content content
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>${title} · db-scheduler-console</title>
    <link rel="stylesheet" href="${ctx.basePath()}/static/console.css">
    <script src="${ctx.basePath()}/static/htmx.min.js" defer></script>
</head>
<body @if(ctx.csrfHeaderJson() != null)hx-headers='${ctx.csrfHeaderJson()}'@endif>
<div class="shell">
    <nav class="nav">
        <span class="brand">db-scheduler-console</span>
        <a href="${ctx.basePath()}/overview" class="@if(ctx.active().equals("overview"))active@endif">Overview</a>
        <a href="${ctx.basePath()}/executions" class="@if(ctx.active().equals("executions"))active@endif">Executions</a>
        <a href="${ctx.basePath()}/recurring" class="@if(ctx.active().equals("recurring"))active@endif">Recurring</a>
        <a href="${ctx.basePath()}/history" class="@if(ctx.active().equals("history"))active@endif">History</a>
        @if(ctx.readOnly())<span class="badge">read-only</span>@endif
    </nav>
    <main id="main">
        ${content}
    </main>
    <div id="flash" class="flash-area"></div>
</div>
</body>
</html>
```

`pages/overview.jte` (skeleton — replaced in Task 11):
```html
@param io.github.logicsatinn.dbscheduler.console.web.PageCtx ctx
@param io.github.logicsatinn.dbscheduler.console.service.StatsService.Tiles tiles
@param boolean historyAvailable
@template.layout(ctx = ctx, title = "Overview", content = @`
    <h1>Overview</h1>
`)
```

- [ ] **Step 9: Run to verify pass**

Run: `./gradlew :console-core:test --tests '*WebInfraTest' --tests '*FmtTest'`
Expected: PASS. (If JTE precompilation fails, run `./gradlew :console-core:generateJte` alone to see template errors.)

- [ ] **Step 10: Commit**

```bash
git add console-core
git commit -m "feat(web): JTE precompiled rendering, layout, vendored htmx/CSS, properties"
```

---

### Task 11: Overview page (tiles, throughput chart, recent failures)

**Files:**
- Create: `console-core/src/main/java/io/github/logicsatinn/dbscheduler/console/web/ChartVm.java`
- Modify: `…/web/OverviewController.java` (add fragment endpoints, recent failures)
- Modify: `console-core/src/main/jte/pages/overview.jte` (full version)
- Create: `console-core/src/main/jte/fragments/tiles.jte`, `fragments/chart.jte`, `fragments/recentFailures.jte`
- Test: `console-core/src/test/java/io/github/logicsatinn/dbscheduler/console/web/OverviewControllerTest.java`

**Interfaces:**
- Consumes: `StatsService.Tiles`, `StatsService.HourBucket`, `HistoryRepository.recentFailures(int)`, `PageCtx`, `Fmt` (Tasks 4, 7, 10).
- Produces:
  - `final class ChartVm`: `record Bar(double x, double width, double ySucceeded, double heightSucceeded, double yFailed, double heightFailed, String tooltip)`; `record Axis(double y, String label)`; `record Model(List<Bar> bars, List<Axis> gridLines, List<Label> xLabels, boolean empty)`; `record Label(double x, String text)`; `static Model of(List<StatsService.HourBucket> buckets)`.
  - Chart geometry: viewBox `0 0 720 170`, plot area x∈[30, 715], baseline y=140, bar width 22, 2px gap between stacked segments, `rx="2"` data ends. Succeeded segment sits on the baseline, failed stacks above it.
  - Fragment GET endpoints (all return HTML fragments, no layout): `/fragments/overview-tiles`, `/fragments/overview-chart`, `/fragments/overview-recent`.

- [ ] **Step 1: Write the failing test**

```java
package io.github.logicsatinn.dbscheduler.console.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.logicsatinn.dbscheduler.console.ConsoleProperties;
import io.github.logicsatinn.dbscheduler.console.data.Dialect;
import io.github.logicsatinn.dbscheduler.console.data.ExecutionRepository;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryEntry;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryRepository;
import io.github.logicsatinn.dbscheduler.console.service.StatsService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

class OverviewControllerTest {

    static final Instant NOW = Instant.parse("2026-07-13T12:30:00Z");

    MockMvc mvc;
    HistoryRepository history;

    @BeforeEach
    void setUp() {
        DataSource ds = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .addScript("db-scheduler-schema/h2.sql")
                .addScript("db-scheduler-console/migrations/h2.sql")
                .build();
        history = new HistoryRepository(ds, Dialect.H2);
        var stats = new StatsService(new ExecutionRepository(ds, "scheduled_tasks", Dialect.H2),
                history, Clock.fixed(NOW, ZoneOffset.UTC));
        var controller = new OverviewController(
                new PageCtxFactory(new ConsoleProperties()), new TemplateRenderer(), stats, history);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .addPlaceholderValue("db-scheduler-console.base-path", "/db-scheduler-console")
                .build();
    }

    void seedHistory() {
        history.insert(new HistoryEntry(0, "email", "1", HistoryEntry.Outcome.SUCCEEDED,
                NOW.minusSeconds(300), NOW.minusSeconds(298), 2000, null, null, null, "n1"));
        history.insert(new HistoryEntry(0, "email", "2", HistoryEntry.Outcome.FAILED,
                NOW.minusSeconds(200), NOW.minusSeconds(199), 1000,
                "java.lang.RuntimeException", "smtp down", "stack", "n1"));
    }

    @Test
    void overviewShowsTilesChartAndRecentFailures() throws Exception {
        seedHistory();
        var body = mvc.perform(get("/db-scheduler-console/overview"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var doc = Jsoup.parse(body);

        assertThat(doc.select(".tile .label").eachText())
                .contains("Scheduled", "Due", "Running", "Failing", "Succeeded · 24h", "Failed · 24h");
        assertThat(doc.select("svg.chart-svg")).hasSize(1);
        assertThat(doc.select("svg rect.viz-good")).hasSize(1);
        assertThat(doc.select("svg rect.viz-critical")).hasSize(1);
        assertThat(doc.select(".legend").text()).contains("Succeeded").contains("Failed");
        assertThat(doc.select("svg title").eachText())
                .anyMatch(t -> t.contains("1 succeeded") && t.contains("1 failed"));
        assertThat(doc.select("#recent-failures td").eachText()).anyMatch(t -> t.contains("smtp down"));
        // polling wired
        assertThat(doc.select("[hx-get$=/fragments/overview-tiles][hx-trigger^=every]")).hasSize(1);
    }

    @Test
    void fragmentsReturnBareHtml() throws Exception {
        seedHistory();
        for (String frag : new String[] {"overview-tiles", "overview-chart", "overview-recent"}) {
            var body = mvc.perform(get("/db-scheduler-console/fragments/" + frag))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain("<html").doesNotContain("<nav");
        }
    }

    @Test
    void chartVmGeometry() {
        var buckets = new java.util.ArrayList<StatsService.HourBucket>();
        Instant start = Instant.parse("2026-07-12T13:00:00Z");
        for (int i = 0; i < 24; i++) {
            buckets.add(new StatsService.HourBucket(start.plusSeconds(i * 3600L),
                    i == 23 ? 10 : 0, i == 23 ? 5 : 0));
        }
        var model = ChartVm.of(buckets);
        assertThat(model.bars()).hasSize(24);
        var last = model.bars().get(23);
        // succeeded sits on the baseline (y + height == 140)
        assertThat(last.ySucceeded() + last.heightSucceeded()).isEqualTo(140.0);
        // failed stacked above with a 2px surface gap
        assertThat(last.yFailed() + last.heightFailed()).isEqualTo(last.ySucceeded() - 2);
        assertThat(model.empty()).isFalse();

        var empty = ChartVm.of(java.util.List.of());
        assertThat(empty.empty()).isTrue();
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :console-core:test --tests '*OverviewControllerTest'`
Expected: FAIL — `ChartVm` missing, template lacks tiles/chart.

- [ ] **Step 3: Implement `ChartVm`**

```java
package io.github.logicsatinn.dbscheduler.console.web;

import io.github.logicsatinn.dbscheduler.console.service.StatsService;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Precomputed SVG geometry for the 24h throughput chart (viewBox 0 0 720 170). */
public final class ChartVm {

    static final double PLOT_LEFT = 30;
    static final double PLOT_RIGHT = 715;
    static final double BASELINE = 140;
    static final double PLOT_TOP = 12;
    static final double BAR_WIDTH = 22;
    static final double SEGMENT_GAP = 2;

    private static final DateTimeFormatter HOUR =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    private ChartVm() {}

    public record Bar(double x, double width, double ySucceeded, double heightSucceeded,
                      double yFailed, double heightFailed, String tooltip) {}

    public record Axis(double y, String label) {}

    public record Label(double x, String text) {}

    public record Model(List<Bar> bars, List<Axis> gridLines, List<Label> xLabels, boolean empty) {}

    public static Model of(List<StatsService.HourBucket> buckets) {
        if (buckets.isEmpty()) {
            return new Model(List.of(), List.of(), List.of(), true);
        }
        long max = Math.max(1, buckets.stream()
                .mapToLong(b -> b.succeeded() + b.failed()).max().orElse(1));
        double plotHeight = BASELINE - PLOT_TOP;
        double slot = (PLOT_RIGHT - PLOT_LEFT) / buckets.size();

        List<Bar> bars = new ArrayList<>();
        for (int i = 0; i < buckets.size(); i++) {
            var b = buckets.get(i);
            double x = PLOT_LEFT + i * slot + (slot - BAR_WIDTH) / 2;
            double hOk = b.succeeded() * plotHeight / max;
            double hFail = b.failed() * plotHeight / max;
            double yOk = BASELINE - hOk;
            double yFail = yOk - (hFail > 0 && hOk > 0 ? SEGMENT_GAP : 0) - hFail;
            String hour = HOUR.format(b.hourStart());
            bars.add(new Bar(x, BAR_WIDTH, yOk, hOk, yFail, hFail,
                    hour + " · " + b.succeeded() + " succeeded, " + b.failed() + " failed"));
        }

        List<Axis> grid = List.of(
                new Axis(BASELINE, "0"),
                new Axis(BASELINE - plotHeight / 2, String.valueOf(Math.round(max / 2.0))),
                new Axis(PLOT_TOP, String.valueOf(max)));

        List<Label> xLabels = new ArrayList<>();
        for (int i = 0; i < buckets.size(); i += 6) {
            xLabels.add(new Label(PLOT_LEFT + i * slot + slot / 2,
                    HOUR.format(buckets.get(i).hourStart())));
        }
        return new Model(bars, grid, xLabels, false);
    }
}
```

- [ ] **Step 4: Extend `OverviewController`**

Replace the `overview` method and add fragments:
```java
    @GetMapping({"", "/", "/overview"})
    public ResponseEntity<String> overview(HttpServletRequest request) {
        return templates.page("pages/overview.jte", model(ctxFactory.page("overview", request)));
    }

    @GetMapping("/fragments/overview-tiles")
    public ResponseEntity<String> tilesFragment() {
        return templates.page("fragments/tiles.jte", Map.of(
                "tiles", stats.tiles(), "historyAvailable", stats.historyAvailable()));
    }

    @GetMapping("/fragments/overview-chart")
    public ResponseEntity<String> chartFragment() {
        return templates.page("fragments/chart.jte", Map.of(
                "chart", ChartVm.of(stats.throughputLast24h())));
    }

    @GetMapping("/fragments/overview-recent")
    public ResponseEntity<String> recentFragment(HttpServletRequest request) {
        return templates.page("fragments/recentFailures.jte", Map.of(
                "failures", stats.historyAvailable() ? history.recentFailures(5) : java.util.List.of(),
                "basePath", ctxFactory.page("overview", request).basePath()));
    }

    private Map<String, Object> model(PageCtx ctx) {
        return Map.of(
                "ctx", ctx,
                "tiles", stats.tiles(),
                "historyAvailable", stats.historyAvailable(),
                "chart", ChartVm.of(stats.throughputLast24h()),
                "failures", stats.historyAvailable() ? history.recentFailures(5) : java.util.List.of());
    }
```

- [ ] **Step 5: Write the templates**

`fragments/tiles.jte`:
```html
@param io.github.logicsatinn.dbscheduler.console.service.StatsService.Tiles tiles
@param boolean historyAvailable
<div class="tiles">
    <div class="tile"><div class="label">Scheduled</div><div class="value">${tiles.scheduled()}</div></div>
    <div class="tile"><div class="label">Due</div><div class="value">${tiles.due()}</div></div>
    <div class="tile"><div class="label">Running</div><div class="value">${tiles.running()}</div></div>
    <div class="tile @if(tiles.failing() > 0)alert@endif"><div class="label">Failing</div><div class="value">${tiles.failing()}</div></div>
    @if(historyAvailable)
        <div class="tile"><div class="label">Succeeded · 24h</div><div class="value">${tiles.succeeded24h()}</div></div>
        <div class="tile"><div class="label">Failed · 24h</div><div class="value">${tiles.failed24h()}</div></div>
    @endif
</div>
```

`fragments/chart.jte`:
```html
@param io.github.logicsatinn.dbscheduler.console.web.ChartVm.Model chart
@if(chart.empty())
    <div class="empty">No execution history yet — history powers this chart.</div>
@else
    <svg class="chart-svg" viewBox="0 0 720 170" role="img" aria-label="Executions per hour, last 24 hours">
        @for(var g : chart.gridLines())
            <line class="chart-grid" x1="30" x2="715" y1="${g.y()}" y2="${g.y()}"/>
            <text class="chart-label" x="26" y="${g.y() + 3}" text-anchor="end">${g.label()}</text>
        @endfor
        @for(var bar : chart.bars())
            <g>
                <title>${bar.tooltip()}</title>
                @if(bar.heightSucceeded() > 0)
                    <rect class="viz-good" x="${bar.x()}" y="${bar.ySucceeded()}" width="${bar.width()}" height="${bar.heightSucceeded()}" rx="2"/>
                @endif
                @if(bar.heightFailed() > 0)
                    <rect class="viz-critical" x="${bar.x()}" y="${bar.yFailed()}" width="${bar.width()}" height="${bar.heightFailed()}" rx="2"/>
                @endif
            </g>
        @endfor
        @for(var label : chart.xLabels())
            <text class="chart-label" x="${label.x()}" y="156" text-anchor="middle">${label.text()}</text>
        @endfor
    </svg>
    <div class="legend">
        <span><span class="key good"></span>Succeeded</span>
        <span><span class="key critical"></span>Failed</span>
    </div>
@endif
```

`fragments/recentFailures.jte`:
```html
@param java.util.List<io.github.logicsatinn.dbscheduler.console.data.history.HistoryEntry> failures
@param String basePath
@if(failures.isEmpty())
    <div class="empty">No recent failures.</div>
@else
    <table class="data" id="recent-failures">
        <thead><tr><th>Task</th><th>Instance</th><th>When</th><th>Error</th></tr></thead>
        <tbody>
        @for(var f : failures)
            <tr>
                <td>${f.taskName()}</td>
                <td><a href="${basePath}/execution?task=${f.taskName()}&id=${f.instanceId()}">${f.instanceId()}</a></td>
                <td class="time">${io.github.logicsatinn.dbscheduler.console.web.Fmt.ts(f.startedAt())}</td>
                <td>${f.exceptionMessage()}</td>
            </tr>
        @endfor
        </tbody>
    </table>
@endif
```

`pages/overview.jte` (full replacement):
```html
@param io.github.logicsatinn.dbscheduler.console.web.PageCtx ctx
@param io.github.logicsatinn.dbscheduler.console.service.StatsService.Tiles tiles
@param boolean historyAvailable
@param io.github.logicsatinn.dbscheduler.console.web.ChartVm.Model chart
@param java.util.List<io.github.logicsatinn.dbscheduler.console.data.history.HistoryEntry> failures
@template.layout(ctx = ctx, title = "Overview", content = @`
    <h1>Overview</h1>
    <div hx-get="${ctx.basePath()}/fragments/overview-tiles" hx-trigger="every ${ctx.pollSeconds()}s" hx-swap="innerHTML">
        @template.fragments.tiles(tiles = tiles, historyAvailable = historyAvailable)
    </div>
    <div class="card">
        <h2>Throughput · last 24 h</h2>
        <div hx-get="${ctx.basePath()}/fragments/overview-chart" hx-trigger="every ${ctx.pollSeconds()}s" hx-swap="innerHTML">
            @template.fragments.chart(chart = chart)
        </div>
    </div>
    <div class="card">
        <h2>Recent failures</h2>
        <div hx-get="${ctx.basePath()}/fragments/overview-recent" hx-trigger="every ${ctx.pollSeconds()}s" hx-swap="innerHTML">
            @template.fragments.recentFailures(failures = failures, basePath = ctx.basePath())
        </div>
    </div>
`)
```

- [ ] **Step 6: Run to verify pass**

Run: `./gradlew :console-core:test --tests '*OverviewControllerTest' --tests '*WebInfraTest'`
Expected: PASS.

- [ ] **Step 7: Render and eyeball it** (chart layout can't be asserted by the validator)

Run: `./gradlew :console-core:test --tests '*OverviewControllerTest' -Ddsc.dump=true` — instead, simplest manual check: temporarily add `System.out.println(body)` in the first test, or wait for Task 18's example app. Non-blocking; the example-app smoke in Task 18 is the real eyeball gate.

- [ ] **Step 8: Commit**

```bash
git add console-core
git commit -m "feat(web): overview page with stat tiles, SVG throughput chart, recent failures"
```

---

### Task 12: Executions page and execution detail

**Files:**
- Create: `…/web/ExecutionsController.java`
- Create: `console-core/src/main/jte/pages/executions.jte`, `pages/executionDetail.jte`, `fragments/executionsTable.jte`
- Test: `…/web/ExecutionsControllerTest.java`

**Interfaces:**
- Consumes: `ExecutionRepository` (page/find/distinctTaskNames), `HistoryRepository.forInstance`, `TaskDataRenderer`, `Fmt`, `PageCtx` (Tasks 3, 4, 9, 10).
- Produces:
  - `GET {base}/executions` — query params: `state` (enum name or blank), `task`, `q`, `from`, `to` (`yyyy-MM-dd'T'HH:mm`, system zone), `page` (default 0), `size` (default 25), `sort` (SortColumn name, default `EXECUTION_TIME`), `dir` (`asc`/`desc`).
  - `GET {base}/execution?task=…&id=…` — detail page; 404 with a friendly page when the execution no longer exists.
  - `ExecutionsController` ctor: `(PageCtxFactory, TemplateRenderer, ExecutionRepository, HistoryRepository, TaskDataRenderer, StatsService, Clock)`.
  - Table region `id="executions-region"`; filter form uses `hx-get` + `hx-select="#executions-region"` + `hx-target="#executions-region"` + `hx-swap="outerHTML"` + `hx-push-url="true"` so filters live in the URL.
  - Row checkboxes (`name="ref"`, `value="taskName::instanceId"`) and header sort links are produced here; the bulk-action toolbar and per-row buttons come in Task 15.

- [ ] **Step 1: Write the failing test**

```java
package io.github.logicsatinn.dbscheduler.console.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.logicsatinn.dbscheduler.console.ConsoleProperties;
import io.github.logicsatinn.dbscheduler.console.data.Dialect;
import io.github.logicsatinn.dbscheduler.console.data.ExecutionRepository;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryEntry;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryRepository;
import io.github.logicsatinn.dbscheduler.console.service.StatsService;
import io.github.logicsatinn.dbscheduler.console.service.TaskDataRenderer;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import javax.sql.DataSource;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ExecutionsControllerTest {

    static final Instant NOW = Instant.parse("2026-07-13T12:00:00Z");

    MockMvc mvc;
    JdbcTemplate jdbc;
    HistoryRepository history;

    @BeforeEach
    void setUp() {
        DataSource ds = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .addScript("db-scheduler-schema/h2.sql")
                .addScript("db-scheduler-console/migrations/h2.sql")
                .build();
        jdbc = new JdbcTemplate(ds);
        var repo = new ExecutionRepository(ds, "scheduled_tasks", Dialect.H2);
        history = new HistoryRepository(ds, Dialect.H2);
        var controller = new ExecutionsController(
                new PageCtxFactory(new ConsoleProperties()), new TemplateRenderer(),
                repo, history, new TaskDataRenderer(null, true),
                new StatsService(repo, history, Clock.fixed(NOW, ZoneOffset.UTC)),
                Clock.fixed(NOW, ZoneOffset.UTC));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .addPlaceholderValue("db-scheduler-console.base-path", "/db-scheduler-console")
                .build();
    }

    void insert(String task, String id, Instant at, boolean picked, int failures, byte[] data) {
        jdbc.update("insert into scheduled_tasks"
                + " (task_name, task_instance, task_data, execution_time, picked, picked_by, consecutive_failures, version)"
                + " values (?, ?, ?, ?, ?, ?, ?, 1)",
                task, id, data, Timestamp.from(at), picked, picked ? "node-1" : null, failures);
    }

    @Test
    void listsAndFiltersByState() throws Exception {
        insert("email", "future", NOW.plus(1, ChronoUnit.HOURS), false, 0, null);
        insert("email", "running", NOW, true, 0, null);

        var doc = Jsoup.parse(mvc.perform(get("/db-scheduler-console/executions?state=RUNNING"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(doc.select("#executions-region tbody tr")).hasSize(1);
        assertThat(doc.select("#executions-region tbody").text()).contains("running");
        // filter form present, URL-synced
        assertThat(doc.select("form[hx-push-url=true]")).hasSize(1);
        // checkbox refs for bulk selection
        assertThat(doc.select("input[name=ref]").attr("value")).isEqualTo("email::running");
    }

    @Test
    void searchPaginationAndSortLinks() throws Exception {
        for (int i = 0; i < 30; i++) {
            insert("bulk", "i-%02d".formatted(i), NOW.plus(i, ChronoUnit.MINUTES), false, 0, null);
        }
        var doc = Jsoup.parse(mvc.perform(get("/db-scheduler-console/executions?q=i-2&size=10"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(doc.select("#executions-region tbody tr")).hasSize(10); // i-20 … i-29
        assertThat(doc.select(".pagination").text()).contains("Page 1 of 1");

        var page2 = Jsoup.parse(mvc.perform(get("/db-scheduler-console/executions?size=10&page=1"))
                .andReturn().getResponse().getContentAsString());
        assertThat(page2.select(".pagination").text()).contains("Page 2 of 3");
        // sort header link toggles direction
        assertThat(page2.select("th a[href*='sort=EXECUTION_TIME']").attr("href")).contains("dir=desc");
    }

    @Test
    void detailShowsDataAndHistory() throws Exception {
        insert("email", "order-1", NOW.plus(1, ChronoUnit.HOURS), false, 0,
                "{\"orderId\":42}".getBytes(StandardCharsets.UTF_8));
        history.insert(new HistoryEntry(0, "email", "order-1", HistoryEntry.Outcome.FAILED,
                NOW.minusSeconds(60), NOW.minusSeconds(58), 2000,
                "java.lang.RuntimeException", "boom", "the-stack-trace", "node-1"));

        var doc = Jsoup.parse(mvc.perform(
                        get("/db-scheduler-console/execution?task=email&id=order-1"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(doc.select("pre.data").text()).contains("orderId");
        assertThat(doc.select("details.stack pre").text()).contains("the-stack-trace");
        assertThat(doc.text()).contains("SCHEDULED");
    }

    @Test
    void missingExecutionIs404() throws Exception {
        mvc.perform(get("/db-scheduler-console/execution?task=email&id=missing"))
                .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :console-core:test --tests '*ExecutionsControllerTest'`
Expected: FAIL — controller does not exist.

- [ ] **Step 3: Implement `ExecutionsController`**

```java
package io.github.logicsatinn.dbscheduler.console.web;

import io.github.logicsatinn.dbscheduler.console.data.ExecutionFilter;
import io.github.logicsatinn.dbscheduler.console.data.ExecutionRepository;
import io.github.logicsatinn.dbscheduler.console.data.ExecutionState;
import io.github.logicsatinn.dbscheduler.console.data.SortColumn;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryRepository;
import io.github.logicsatinn.dbscheduler.console.service.StatsService;
import io.github.logicsatinn.dbscheduler.console.service.TaskDataRenderer;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("${db-scheduler-console.base-path:/db-scheduler-console}")
public class ExecutionsController {

    private final PageCtxFactory ctxFactory;
    private final TemplateRenderer templates;
    private final ExecutionRepository executions;
    private final HistoryRepository history;
    private final TaskDataRenderer taskData;
    private final StatsService stats;
    private final Clock clock;

    public ExecutionsController(PageCtxFactory ctxFactory, TemplateRenderer templates,
            ExecutionRepository executions, HistoryRepository history,
            TaskDataRenderer taskData, StatsService stats, Clock clock) {
        this.ctxFactory = ctxFactory;
        this.templates = templates;
        this.executions = executions;
        this.history = history;
        this.taskData = taskData;
        this.stats = stats;
        this.clock = clock;
    }

    @GetMapping("/executions")
    public ResponseEntity<String> executions(
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String task,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "EXECUTION_TIME") String sort,
            @RequestParam(defaultValue = "asc") String dir,
            HttpServletRequest request) {
        Instant now = clock.instant();
        SortColumn sortColumn = parseEnum(SortColumn.class, sort, SortColumn.EXECUTION_TIME);
        boolean desc = "desc".equalsIgnoreCase(dir);
        var filter = new ExecutionFilter(
                parseEnum(ExecutionState.class, state, null),
                blankToNull(task), blankToNull(q),
                parseLocal(from), parseLocal(to),
                Math.max(0, page), clamp(size, 1, 200), sortColumn, desc);

        Map<String, Object> model = new HashMap<>();
        model.put("ctx", ctxFactory.page("executions", request));
        model.put("filter", filter);
        model.put("stateParam", state == null ? "" : state);
        model.put("fromParam", from == null ? "" : from);
        model.put("toParam", to == null ? "" : to);
        model.put("page", executions.page(filter, now));
        model.put("taskNames", executions.distinctTaskNames());
        model.put("now", now);
        model.put("queryBase", queryBase(state, task, q, from, to, size));
        return templates.page("pages/executions.jte", model);
    }

    @GetMapping("/execution")
    public ResponseEntity<String> detail(@RequestParam String task, @RequestParam String id,
            HttpServletRequest request) {
        var row = executions.find(task, id);
        if (row.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.valueOf("text/html;charset=UTF-8"))
                    .body("<p>Execution not found: it may have completed and left the live table.</p>");
        }
        Map<String, Object> model = new HashMap<>();
        model.put("ctx", ctxFactory.page("executions", request));
        model.put("row", row.get());
        model.put("now", clock.instant());
        model.put("dataPreview", taskData.render(row.get().taskData()));
        model.put("historyEntries", stats.historyAvailable()
                ? history.forInstance(task, id, 50) : List.of());
        return templates.page("pages/executionDetail.jte", model);
    }

    /** Query string with every filter except page — pagination/sort links append to it. */
    private static String queryBase(String state, String task, String q, String from, String to, int size) {
        StringBuilder sb = new StringBuilder("?size=").append(size);
        appendParam(sb, "state", state);
        appendParam(sb, "task", task);
        appendParam(sb, "q", q);
        appendParam(sb, "from", from);
        appendParam(sb, "to", to);
        return sb.toString();
    }

    private static void appendParam(StringBuilder sb, String name, String value) {
        if (value != null && !value.isBlank()) {
            sb.append('&').append(name).append('=')
              .append(java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, E fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private Instant parseLocal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static int clamp(int v, int min, int max) {
        return Math.min(max, Math.max(min, v));
    }
}
```

- [ ] **Step 4: Write the templates**

`pages/executions.jte`:
```html
@param io.github.logicsatinn.dbscheduler.console.web.PageCtx ctx
@param io.github.logicsatinn.dbscheduler.console.data.ExecutionFilter filter
@param String stateParam
@param String fromParam
@param String toParam
@param io.github.logicsatinn.dbscheduler.console.data.Page<io.github.logicsatinn.dbscheduler.console.data.ExecutionRow> page
@param java.util.List<String> taskNames
@param java.time.Instant now
@param String queryBase
@template.layout(ctx = ctx, title = "Executions", content = @`
    <h1>Executions</h1>
    <form class="filters"
          hx-get="${ctx.basePath()}/executions"
          hx-target="#executions-region" hx-select="#executions-region" hx-swap="outerHTML"
          hx-push-url="true"
          hx-trigger="change, submit, input delay:500ms from:find input[name='q']">
        <label>State
            <select name="state">
                <option value="">All</option>
                @for(var s : io.github.logicsatinn.dbscheduler.console.data.ExecutionState.values())
                    <option value="${s.name()}" selected="${s.name().equals(stateParam)}">${s.name()}</option>
                @endfor
            </select>
        </label>
        <label>Task
            <select name="task">
                <option value="">All</option>
                @for(var name : taskNames)
                    <option value="${name}" selected="${name.equals(filter.taskName())}">${name}</option>
                @endfor
            </select>
        </label>
        <label>Instance contains
            <input type="search" name="q" value="${filter.instanceContains()}" placeholder="instance id">
        </label>
        <label>From
            <input type="datetime-local" name="from" value="${fromParam}">
        </label>
        <label>To
            <input type="datetime-local" name="to" value="${toParam}">
        </label>
        <input type="hidden" name="size" value="${filter.pageSize()}">
        <button type="submit">Apply</button>
    </form>
    @template.fragments.executionsTable(ctx = ctx, page = page, filter = filter, now = now, queryBase = queryBase)
`)
```

Note on jte: `selected="${boolean}"` renders the attribute only when true (jte's smart attributes), which is exactly what `<option>` needs.

`fragments/executionsTable.jte`:
```html
@param io.github.logicsatinn.dbscheduler.console.web.PageCtx ctx
@param io.github.logicsatinn.dbscheduler.console.data.Page<io.github.logicsatinn.dbscheduler.console.data.ExecutionRow> page
@param io.github.logicsatinn.dbscheduler.console.data.ExecutionFilter filter
@param java.time.Instant now
@param String queryBase
!{var base = ctx.basePath();}
!{var sortDir = filter.descending() ? "asc" : "desc";}
<div id="executions-region">
    <table class="data">
        <thead>
        <tr>
            <th></th>
            <th><a href="${base}/executions${queryBase}&sort=TASK_NAME&dir=${filter.sort().name().equals("TASK_NAME") ? sortDir : "asc"}">Task</a></th>
            <th>Instance</th>
            <th>State</th>
            <th><a href="${base}/executions${queryBase}&sort=EXECUTION_TIME&dir=${filter.sort().name().equals("EXECUTION_TIME") ? sortDir : "asc"}">Execution time</a></th>
            <th><a href="${base}/executions${queryBase}&sort=CONSECUTIVE_FAILURES&dir=${filter.sort().name().equals("CONSECUTIVE_FAILURES") ? sortDir : "desc"}">Failures</a></th>
            <th>Picked by</th>
            <th><a href="${base}/executions${queryBase}&sort=LAST_HEARTBEAT&dir=${filter.sort().name().equals("LAST_HEARTBEAT") ? sortDir : "asc"}">Heartbeat</a></th>
        </tr>
        </thead>
        <tbody>
        @if(page.items().isEmpty())
            <tr><td colspan="8" class="empty">No executions match this filter.</td></tr>
        @endif
        @for(var row : page.items())
            !{var state = row.state(now);}
            <tr>
                <td><input type="checkbox" name="ref" value="${row.taskName()}::${row.instanceId()}" form="bulk-form"></td>
                <td>${row.taskName()}</td>
                <td><a href="${base}/execution?task=${row.taskName()}&id=${row.instanceId()}">${row.instanceId()}</a></td>
                <td><span class="state ${state.name().toLowerCase()}">${state.name()}</span></td>
                <td class="time">${io.github.logicsatinn.dbscheduler.console.web.Fmt.ts(row.executionTime())}</td>
                <td class="num">${row.consecutiveFailures()}</td>
                <td>${row.pickedBy()}</td>
                <td class="time">${io.github.logicsatinn.dbscheduler.console.web.Fmt.ts(row.lastHeartbeat())}</td>
            </tr>
        @endfor
        </tbody>
    </table>
    <div class="pagination">
        @if(filter.page() > 0)
            <a href="${base}/executions${queryBase}&sort=${filter.sort().name()}&dir=${filter.descending() ? "desc" : "asc"}&page=${filter.page() - 1}">‹ Prev</a>
        @endif
        <span>Page ${filter.page() + 1} of ${Math.max(1, page.totalPages())} · ${page.total()} total</span>
        @if(filter.page() + 1 < page.totalPages())
            <a href="${base}/executions${queryBase}&sort=${filter.sort().name()}&dir=${filter.descending() ? "desc" : "asc"}&page=${filter.page() + 1}">Next ›</a>
        @endif
    </div>
</div>
```

`pages/executionDetail.jte`:
```html
@param io.github.logicsatinn.dbscheduler.console.web.PageCtx ctx
@param io.github.logicsatinn.dbscheduler.console.data.ExecutionRow row
@param java.time.Instant now
@param String dataPreview
@param java.util.List<io.github.logicsatinn.dbscheduler.console.data.history.HistoryEntry> historyEntries
@template.layout(ctx = ctx, title = row.taskName() + " / " + row.instanceId(), content = @`
    <h1>${row.taskName()} <span class="muted">/</span> ${row.instanceId()}</h1>
    <div class="card">
        <h2>Execution</h2>
        <table class="data">
            <tbody>
            <tr><th>State</th><td><span class="state ${row.state(now).name().toLowerCase()}">${row.state(now).name()}</span></td></tr>
            <tr><th>Execution time</th><td class="time">${io.github.logicsatinn.dbscheduler.console.web.Fmt.ts(row.executionTime())}</td></tr>
            <tr><th>Picked by</th><td>${row.pickedBy()}</td></tr>
            <tr><th>Consecutive failures</th><td class="num">${row.consecutiveFailures()}</td></tr>
            <tr><th>Last success</th><td class="time">${io.github.logicsatinn.dbscheduler.console.web.Fmt.ts(row.lastSuccess())}</td></tr>
            <tr><th>Last failure</th><td class="time">${io.github.logicsatinn.dbscheduler.console.web.Fmt.ts(row.lastFailure())}</td></tr>
            <tr><th>Version</th><td class="num">${row.version()}</td></tr>
            </tbody>
        </table>
    </div>
    <div class="card">
        <h2>Task data</h2>
        <pre class="data">${dataPreview}</pre>
    </div>
    <div class="card">
        <h2>History</h2>
        @if(historyEntries.isEmpty())
            <div class="empty">No recorded history for this instance.</div>
        @else
            <table class="data">
                <thead><tr><th>Outcome</th><th>Started</th><th>Duration</th><th>Node</th><th>Error</th></tr></thead>
                <tbody>
                @for(var h : historyEntries)
                    <tr>
                        <td><span class="state ${h.outcome().name().toLowerCase()}">${h.outcome().name()}</span></td>
                        <td class="time">${io.github.logicsatinn.dbscheduler.console.web.Fmt.ts(h.startedAt())}</td>
                        <td class="num">${io.github.logicsatinn.dbscheduler.console.web.Fmt.duration(h.durationMs())}</td>
                        <td>${h.pickedBy()}</td>
                        <td>
                            @if(h.stacktrace() != null)
                                <details class="stack"><summary>${h.exceptionMessage()}</summary><pre>${h.stacktrace()}</pre></details>
                            @else
                                ${h.exceptionMessage()}
                            @endif
                        </td>
                    </tr>
                @endfor
                </tbody>
            </table>
        @endif
    </div>
`)
```

- [ ] **Step 5: Run to verify pass**

Run: `./gradlew :console-core:test --tests '*ExecutionsControllerTest'`
Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add console-core
git commit -m "feat(web): executions list with URL-synced filters, sorting, paging; execution detail"
```

---

### Task 13: Recurring tasks view

**Files:**
- Create: `…/service/RecurringTasksService.java`
- Create: `…/web/RecurringController.java`
- Create: `console-core/src/main/jte/pages/recurring.jte`
- Modify: `…/data/ExecutionRepository.java` — add `Optional<Instant> nextExecutionTime(String taskName)`
- Modify: `…/data/history/HistoryRepository.java` — add `Optional<HistoryEntry> latestForTask(String taskName)`
- Test: `…/service/RecurringTasksServiceTest.java`, `…/web/RecurringControllerTest.java`

**Interfaces:**
- Consumes: injected `List<Task<?>>` (the app's task beans — db-scheduler `Task.getName()`; instance-of checks against `RecurringTask` / `OneTimeTask`), Tasks 3–4 repositories.
- Produces:
  - `ExecutionRepository.nextExecutionTime(String taskName)` — `SELECT MIN(execution_time) … WHERE task_name = ? AND picked = false`.
  - `HistoryRepository.latestForTask(String taskName)` — newest entry for any instance of the task.
  - `class RecurringTasksService` — ctor `(List<Task<?>>, ExecutionRepository, HistoryRepository)`; `record Row(String taskName, String type, Instant nextExecution, HistoryEntry lastRun)`; `List<Row> rows()` sorted by task name; `type` ∈ `"Recurring" | "One-time" | "Custom"`; `lastRun` null when history is missing/empty (check `history.tableExists()` once, same caching rule as StatsService).
  - `GET {base}/recurring` renders the table.
  - (If `Task.getName()` does not compile against 16.12.0, the accessor is `getTaskName()` — check the `Task` class in the IDE; everything else is unchanged.)

- [ ] **Step 1: Write the failing service test**

```java
package io.github.logicsatinn.dbscheduler.console.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import io.github.logicsatinn.dbscheduler.console.data.Dialect;
import io.github.logicsatinn.dbscheduler.console.data.ExecutionRepository;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryEntry;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

class RecurringTasksServiceTest {

    static final Instant NOW = Instant.parse("2026-07-13T12:00:00Z");

    DataSource ds;
    JdbcTemplate jdbc;
    RecurringTasksService service;
    HistoryRepository history;

    static final List<Task<?>> KNOWN = List.of(
            Tasks.recurring("cleanup", FixedDelay.ofHours(1)).execute((i, c) -> {}),
            Tasks.oneTime("email-send").execute((i, c) -> {}));

    @BeforeEach
    void setUp() {
        ds = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .addScript("db-scheduler-schema/h2.sql")
                .addScript("db-scheduler-console/migrations/h2.sql")
                .build();
        jdbc = new JdbcTemplate(ds);
        history = new HistoryRepository(ds, Dialect.H2);
        service = new RecurringTasksService(KNOWN,
                new ExecutionRepository(ds, "scheduled_tasks", Dialect.H2), history);
    }

    @Test
    void rowsShowTypeNextRunAndLastOutcome() {
        jdbc.update("insert into scheduled_tasks (task_name, task_instance, execution_time, picked, version)"
                + " values ('cleanup', 'recurring', ?, false, 1)",
                Timestamp.from(NOW.plusSeconds(1800)));
        history.insert(new HistoryEntry(0, "cleanup", "recurring", HistoryEntry.Outcome.SUCCEEDED,
                NOW.minusSeconds(1800), NOW.minusSeconds(1798), 2000, null, null, null, "n1"));

        var rows = service.rows();
        assertThat(rows).hasSize(2);
        // sorted by name: cleanup, email-send
        assertThat(rows.get(0).taskName()).isEqualTo("cleanup");
        assertThat(rows.get(0).type()).isEqualTo("Recurring");
        assertThat(rows.get(0).nextExecution()).isEqualTo(NOW.plusSeconds(1800));
        assertThat(rows.get(0).lastRun().outcome()).isEqualTo(HistoryEntry.Outcome.SUCCEEDED);
        assertThat(rows.get(1).taskName()).isEqualTo("email-send");
        assertThat(rows.get(1).type()).isEqualTo("One-time");
        assertThat(rows.get(1).nextExecution()).isNull();
        assertThat(rows.get(1).lastRun()).isNull();
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :console-core:test --tests '*RecurringTasksServiceTest'`
Expected: FAIL.

- [ ] **Step 3: Add the two repository methods**

In `ExecutionRepository`:
```java
    public java.util.Optional<Instant> nextExecutionTime(String taskName) {
        List<Timestamp> result = jdbc.query(
                "SELECT MIN(execution_time) AS next_time FROM " + table
                        + " WHERE task_name = ? AND picked = ?",
                (rs, i) -> rs.getTimestamp("next_time"), taskName, false);
        return result.stream().filter(java.util.Objects::nonNull).findFirst().map(Timestamp::toInstant);
    }
```

In `HistoryRepository`:
```java
    public java.util.Optional<HistoryEntry> latestForTask(String taskName) {
        String sql = "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE task_name = ?"
                + " ORDER BY started_at DESC, id DESC" + dialect.paginationClause();
        List<Object> params = new ArrayList<>(List.of(taskName));
        params.addAll(Arrays.asList(dialect.paginationParams(0, 1)));
        return jdbc.query(sql, ROW_MAPPER, params.toArray()).stream().findFirst();
    }
```

- [ ] **Step 4: Implement `RecurringTasksService`**

```java
package io.github.logicsatinn.dbscheduler.console.service;

import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import io.github.logicsatinn.dbscheduler.console.data.ExecutionRepository;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryEntry;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

public class RecurringTasksService {

    private final List<Task<?>> knownTasks;
    private final ExecutionRepository executions;
    private final HistoryRepository history;
    private volatile boolean historyTableSeen;

    public RecurringTasksService(List<Task<?>> knownTasks,
            ExecutionRepository executions, HistoryRepository history) {
        this.knownTasks = knownTasks;
        this.executions = executions;
        this.history = history;
    }

    public record Row(String taskName, String type, Instant nextExecution, HistoryEntry lastRun) {}

    public List<Row> rows() {
        boolean withHistory = historyAvailable();
        return knownTasks.stream()
                .sorted(Comparator.comparing(Task::getName))
                .map(task -> new Row(
                        task.getName(),
                        typeOf(task),
                        executions.nextExecutionTime(task.getName()).orElse(null),
                        withHistory ? history.latestForTask(task.getName()).orElse(null) : null))
                .toList();
    }

    private boolean historyAvailable() {
        if (!historyTableSeen) {
            historyTableSeen = history.tableExists();
        }
        return historyTableSeen;
    }

    private static String typeOf(Task<?> task) {
        if (task instanceof RecurringTask<?>) return "Recurring";
        if (task instanceof OneTimeTask<?>) return "One-time";
        return "Custom";
    }
}
```

- [ ] **Step 5: Run the service test**

Run: `./gradlew :console-core:test --tests '*RecurringTasksServiceTest'`
Expected: PASS.

- [ ] **Step 6: Write the failing controller test**

```java
package io.github.logicsatinn.dbscheduler.console.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import io.github.logicsatinn.dbscheduler.console.ConsoleProperties;
import io.github.logicsatinn.dbscheduler.console.data.Dialect;
import io.github.logicsatinn.dbscheduler.console.data.ExecutionRepository;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryRepository;
import io.github.logicsatinn.dbscheduler.console.service.RecurringTasksService;
import java.util.List;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RecurringControllerTest {

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        var ds = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .addScript("db-scheduler-schema/h2.sql")
                .addScript("db-scheduler-console/migrations/h2.sql")
                .build();
        List<Task<?>> known = List.of(
                Tasks.recurring("cleanup", FixedDelay.ofHours(1)).execute((i, c) -> {}));
        var service = new RecurringTasksService(known,
                new ExecutionRepository(ds, "scheduled_tasks", Dialect.H2),
                new HistoryRepository(ds, Dialect.H2));
        var controller = new RecurringController(
                new PageCtxFactory(new ConsoleProperties()), new TemplateRenderer(), service);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .addPlaceholderValue("db-scheduler-console.base-path", "/db-scheduler-console")
                .build();
    }

    @Test
    void listsKnownTasks() throws Exception {
        var doc = Jsoup.parse(mvc.perform(get("/db-scheduler-console/recurring"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(doc.select("table.data tbody tr")).hasSize(1);
        assertThat(doc.select("tbody").text()).contains("cleanup").contains("Recurring");
    }
}
```

- [ ] **Step 7: Implement controller and template**

`RecurringController.java`:
```java
package io.github.logicsatinn.dbscheduler.console.web;

import io.github.logicsatinn.dbscheduler.console.service.RecurringTasksService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("${db-scheduler-console.base-path:/db-scheduler-console}")
public class RecurringController {

    private final PageCtxFactory ctxFactory;
    private final TemplateRenderer templates;
    private final RecurringTasksService service;

    public RecurringController(PageCtxFactory ctxFactory, TemplateRenderer templates,
            RecurringTasksService service) {
        this.ctxFactory = ctxFactory;
        this.templates = templates;
        this.service = service;
    }

    @GetMapping("/recurring")
    public ResponseEntity<String> recurring(HttpServletRequest request) {
        return templates.page("pages/recurring.jte", Map.of(
                "ctx", ctxFactory.page("recurring", request),
                "rows", service.rows()));
    }
}
```

`pages/recurring.jte`:
```html
@param io.github.logicsatinn.dbscheduler.console.web.PageCtx ctx
@param java.util.List<io.github.logicsatinn.dbscheduler.console.service.RecurringTasksService.Row> rows
@template.layout(ctx = ctx, title = "Recurring tasks", content = @`
    <h1>Task definitions</h1>
    <div class="card">
        @if(rows.isEmpty())
            <div class="empty">No task beans found in the application context.</div>
        @else
            <table class="data">
                <thead><tr><th>Task</th><th>Type</th><th>Next run</th><th>Last outcome</th><th></th></tr></thead>
                <tbody>
                @for(var row : rows)
                    <tr>
                        <td>${row.taskName()}</td>
                        <td>${row.type()}</td>
                        <td class="time">${io.github.logicsatinn.dbscheduler.console.web.Fmt.ts(row.nextExecution())}</td>
                        <td>
                            @if(row.lastRun() != null)
                                <span class="state ${row.lastRun().outcome().name().toLowerCase()}">${row.lastRun().outcome().name()}</span>
                                <span class="muted">${io.github.logicsatinn.dbscheduler.console.web.Fmt.ts(row.lastRun().startedAt())} · ${io.github.logicsatinn.dbscheduler.console.web.Fmt.duration(row.lastRun().durationMs())}</span>
                            @else
                                <span class="muted">—</span>
                            @endif
                        </td>
                        <td><a href="${ctx.basePath()}/history?task=${row.taskName()}">history</a></td>
                    </tr>
                @endfor
                </tbody>
            </table>
        @endif
    </div>
`)
```

- [ ] **Step 8: Run all touched tests**

Run: `./gradlew :console-core:test --tests '*Recurring*' --tests '*ExecutionRepositoryTest' --tests '*HistoryRepositoryTest'`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add console-core
git commit -m "feat(web): recurring task definitions view with next run and last outcome"
```

---

### Task 14: History page with graceful degradation

**Files:**
- Create: `…/web/HistoryController.java`
- Create: `console-core/src/main/jte/pages/history.jte`, `pages/historySetup.jte`, `fragments/historyTable.jte`
- Test: `…/web/HistoryControllerTest.java`

**Interfaces:**
- Consumes: `HistoryRepository` (page/tableExists/createTableScript), `Dialect`, `Fmt`, `PageCtx`.
- Produces:
  - `GET {base}/history` — params `task`, `outcome` (SUCCEEDED/FAILED), `q` (text search), `from`, `to`, `page`, `size` (default 25). Same `hx-select` region pattern as executions (`id="history-region"`).
  - `HistoryController` ctor: `(PageCtxFactory, TemplateRenderer, HistoryRepository, Dialect)`.
  - Missing table → 200 with `pages/historySetup.jte`: explains setup, shows dialect name and the `CREATE TABLE` script in a `<pre>`.

- [ ] **Step 1: Write the failing test**

```java
package io.github.logicsatinn.dbscheduler.console.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.logicsatinn.dbscheduler.console.ConsoleProperties;
import io.github.logicsatinn.dbscheduler.console.data.Dialect;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryEntry;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryRepository;
import java.time.Instant;
import javax.sql.DataSource;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class HistoryControllerTest {

    static final Instant NOW = Instant.parse("2026-07-13T12:00:00Z");

    MockMvc mvcFor(boolean withHistoryTable) {
        var builder = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .addScript("db-scheduler-schema/h2.sql");
        if (withHistoryTable) {
            builder.addScript("db-scheduler-console/migrations/h2.sql");
        }
        DataSource ds = builder.build();
        var repo = new HistoryRepository(ds, Dialect.H2);
        if (withHistoryTable) {
            repo.insert(new HistoryEntry(0, "email", "1", HistoryEntry.Outcome.FAILED,
                    NOW.minusSeconds(60), NOW.minusSeconds(58), 2000,
                    "java.lang.RuntimeException", "smtp down", "the-stack", "n1"));
            repo.insert(new HistoryEntry(0, "report", "2", HistoryEntry.Outcome.SUCCEEDED,
                    NOW.minusSeconds(30), NOW.minusSeconds(29), 1000, null, null, null, "n1"));
        }
        var controller = new HistoryController(
                new PageCtxFactory(new ConsoleProperties()), new TemplateRenderer(), repo, Dialect.H2);
        return MockMvcBuilders.standaloneSetup(controller)
                .addPlaceholderValue("db-scheduler-console.base-path", "/db-scheduler-console")
                .build();
    }

    @Test
    void listsAndFilters() throws Exception {
        var mvc = mvcFor(true);
        var doc = Jsoup.parse(mvc.perform(get("/db-scheduler-console/history"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(doc.select("#history-region tbody tr")).hasSize(2);

        var failed = Jsoup.parse(mvc.perform(get("/db-scheduler-console/history?outcome=FAILED"))
                .andReturn().getResponse().getContentAsString());
        assertThat(failed.select("#history-region tbody tr")).hasSize(1);
        assertThat(failed.select("details.stack pre").text()).contains("the-stack");

        var text = Jsoup.parse(mvc.perform(get("/db-scheduler-console/history?q=smtp"))
                .andReturn().getResponse().getContentAsString());
        assertThat(text.select("#history-region tbody tr")).hasSize(1);
    }

    @Test
    void missingTableShowsSetupPage() throws Exception {
        var mvc = mvcFor(false);
        var doc = Jsoup.parse(mvc.perform(get("/db-scheduler-console/history"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(doc.text()).contains("history table has not been created");
        assertThat(doc.select("pre").text()).contains("create table dsc_execution_history");
        assertThat(doc.text()).contains("H2");
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :console-core:test --tests '*HistoryControllerTest'`
Expected: FAIL.

- [ ] **Step 3: Implement `HistoryController`**

```java
package io.github.logicsatinn.dbscheduler.console.web;

import io.github.logicsatinn.dbscheduler.console.data.Dialect;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryEntry;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryFilter;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("${db-scheduler-console.base-path:/db-scheduler-console}")
public class HistoryController {

    private final PageCtxFactory ctxFactory;
    private final TemplateRenderer templates;
    private final HistoryRepository history;
    private final Dialect dialect;

    public HistoryController(PageCtxFactory ctxFactory, TemplateRenderer templates,
            HistoryRepository history, Dialect dialect) {
        this.ctxFactory = ctxFactory;
        this.templates = templates;
        this.history = history;
        this.dialect = dialect;
    }

    @GetMapping("/history")
    public ResponseEntity<String> history(
            @RequestParam(required = false) String task,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            HttpServletRequest request) {
        var ctx = ctxFactory.page("history", request);
        if (!history.tableExists()) {
            return templates.page("pages/historySetup.jte", Map.of(
                    "ctx", ctx,
                    "dialectName", dialect.name(),
                    "script", history.createTableScript()));
        }
        HistoryEntry.Outcome parsedOutcome = null;
        if (outcome != null && !outcome.isBlank()) {
            try {
                parsedOutcome = HistoryEntry.Outcome.valueOf(outcome.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // unknown value = no outcome filter
            }
        }
        var filter = new HistoryFilter(
                blankToNull(task), parsedOutcome, blankToNull(q),
                parseLocal(from), parseLocal(to),
                Math.max(0, page), Math.min(200, Math.max(1, size)));

        Map<String, Object> model = new HashMap<>();
        model.put("ctx", ctx);
        model.put("filter", filter);
        model.put("outcomeParam", outcome == null ? "" : outcome);
        model.put("fromParam", from == null ? "" : from);
        model.put("toParam", to == null ? "" : to);
        model.put("page", history.page(filter));
        model.put("queryBase", queryBase(task, outcome, q, from, to, size));
        return templates.page("pages/history.jte", model);
    }

    private static String queryBase(String task, String outcome, String q, String from, String to, int size) {
        StringBuilder sb = new StringBuilder("?size=").append(size);
        appendParam(sb, "task", task);
        appendParam(sb, "outcome", outcome);
        appendParam(sb, "q", q);
        appendParam(sb, "from", from);
        appendParam(sb, "to", to);
        return sb.toString();
    }

    private static void appendParam(StringBuilder sb, String name, String value) {
        if (value != null && !value.isBlank()) {
            sb.append('&').append(name).append('=')
              .append(java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private Instant parseLocal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
```

- [ ] **Step 4: Write the templates**

`pages/history.jte`:
```html
@param io.github.logicsatinn.dbscheduler.console.web.PageCtx ctx
@param io.github.logicsatinn.dbscheduler.console.data.history.HistoryFilter filter
@param String outcomeParam
@param String fromParam
@param String toParam
@param io.github.logicsatinn.dbscheduler.console.data.Page<io.github.logicsatinn.dbscheduler.console.data.history.HistoryEntry> page
@param String queryBase
@template.layout(ctx = ctx, title = "History", content = @`
    <h1>Execution history</h1>
    <form class="filters"
          hx-get="${ctx.basePath()}/history"
          hx-target="#history-region" hx-select="#history-region" hx-swap="outerHTML"
          hx-push-url="true"
          hx-trigger="change, submit, input delay:500ms from:find input[name='q']">
        <label>Task
            <input type="search" name="task" value="${filter.taskName()}" placeholder="task name">
        </label>
        <label>Outcome
            <select name="outcome">
                <option value="">All</option>
                <option value="SUCCEEDED" selected="${outcomeParam.equalsIgnoreCase("SUCCEEDED")}">Succeeded</option>
                <option value="FAILED" selected="${outcomeParam.equalsIgnoreCase("FAILED")}">Failed</option>
            </select>
        </label>
        <label>Contains
            <input type="search" name="q" value="${filter.textSearch()}" placeholder="instance or error text">
        </label>
        <label>From <input type="datetime-local" name="from" value="${fromParam}"></label>
        <label>To <input type="datetime-local" name="to" value="${toParam}"></label>
        <input type="hidden" name="size" value="${filter.pageSize()}">
        <button type="submit">Apply</button>
    </form>
    @template.fragments.historyTable(ctx = ctx, page = page, filter = filter, queryBase = queryBase)
`)
```

`fragments/historyTable.jte`:
```html
@param io.github.logicsatinn.dbscheduler.console.web.PageCtx ctx
@param io.github.logicsatinn.dbscheduler.console.data.Page<io.github.logicsatinn.dbscheduler.console.data.history.HistoryEntry> page
@param io.github.logicsatinn.dbscheduler.console.data.history.HistoryFilter filter
@param String queryBase
<div id="history-region">
    <table class="data">
        <thead><tr><th>Task</th><th>Instance</th><th>Outcome</th><th>Started</th><th>Duration</th><th>Node</th><th>Error</th></tr></thead>
        <tbody>
        @if(page.items().isEmpty())
            <tr><td colspan="7" class="empty">No history entries match this filter.</td></tr>
        @endif
        @for(var h : page.items())
            <tr>
                <td>${h.taskName()}</td>
                <td><a href="${ctx.basePath()}/execution?task=${h.taskName()}&id=${h.instanceId()}">${h.instanceId()}</a></td>
                <td><span class="state ${h.outcome().name().toLowerCase()}">${h.outcome().name()}</span></td>
                <td class="time">${io.github.logicsatinn.dbscheduler.console.web.Fmt.ts(h.startedAt())}</td>
                <td class="num">${io.github.logicsatinn.dbscheduler.console.web.Fmt.duration(h.durationMs())}</td>
                <td>${h.pickedBy()}</td>
                <td>
                    @if(h.stacktrace() != null)
                        <details class="stack"><summary>${h.exceptionMessage()}</summary><pre>${h.stacktrace()}</pre></details>
                    @elseif(h.exceptionMessage() != null)
                        ${h.exceptionMessage()}
                    @endif
                </td>
            </tr>
        @endfor
        </tbody>
    </table>
    <div class="pagination">
        @if(filter.page() > 0)
            <a href="${ctx.basePath()}/history${queryBase}&page=${filter.page() - 1}">‹ Prev</a>
        @endif
        <span>Page ${filter.page() + 1} of ${Math.max(1, page.totalPages())} · ${page.total()} total</span>
        @if(filter.page() + 1 < page.totalPages())
            <a href="${ctx.basePath()}/history${queryBase}&page=${filter.page() + 1}">Next ›</a>
        @endif
    </div>
</div>
```

`pages/historySetup.jte`:
```html
@param io.github.logicsatinn.dbscheduler.console.web.PageCtx ctx
@param String dialectName
@param String script
@template.layout(ctx = ctx, title = "History setup", content = @`
    <h1>Execution history</h1>
    <div class="card">
        <h2>Setup required</h2>
        <p>The history table has not been created yet. Live views work without it; history,
           the 24h stats, and the throughput chart need it.</p>
        <p>Detected database: <strong>${dialectName}</strong>. Apply this migration with your
           usual tool (Flyway, Liquibase, or manually):</p>
        <pre class="data">${script}</pre>
    </div>
`)
```

- [ ] **Step 5: Run to verify pass**

Run: `./gradlew :console-core:test --tests '*HistoryControllerTest'`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add console-core
git commit -m "feat(web): history view with filters and setup page when table is missing"
```

---

### Task 15: Action endpoints, flash messages, read-only enforcement, CSRF

**Files:**
- Create: `…/web/ActionsController.java`
- Create: `console-core/src/main/jte/fragments/flash.jte`
- Modify: `console-core/src/main/jte/fragments/executionsTable.jte` (bulk toolbar, Actions column, refresh listener)
- Modify: `console-core/src/main/jte/pages/executionDetail.jte` (Run now + Reschedule forms, refresh listener)
- Modify: `…/web/ExecutionsController.java` (add `selfUrl` and `refreshUrl` model entries)
- Test: `…/web/ActionsControllerTest.java`, `…/web/CsrfRenderingTest.java`

**Interfaces:**
- Consumes: `ExecutionActions` (Task 8), `ConsoleProperties.isReadOnly()`, `CsrfSupport`, `TemplateRenderer`.
- Produces:
  - `ActionsController` ctor: `(ConsoleProperties, TemplateRenderer, ExecutionActions)`.
  - `POST {base}/actions/run-now` (form params `task`, `id`), `POST {base}/actions/reschedule` (`task`, `id`, `when` as `yyyy-MM-dd'T'HH:mm`), `POST {base}/actions/delete` (`task`, `id`), `POST {base}/actions/bulk` (`kind` = `RUN_NOW`|`DELETE`, repeated `ref` = `taskName::instanceId`).
  - Every action response: body = rendered `fragments/flash.jte`, header `HX-Trigger: dsc-refresh`. Status 200 on handled actions (success or business failure — the flash tells the difference), **403 when read-only** (flash body explains).
  - Page regions listen with `hx-trigger="dsc-refresh from:body"` and re-GET themselves (`hx-select` swap), so tables refresh after any action.
  - Delete is offered in the table + bulk only (not on the detail page, which would 404 after its own delete).

- [ ] **Step 1: Write the failing tests**

`ActionsControllerTest.java`:
```java
package io.github.logicsatinn.dbscheduler.console.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import io.github.logicsatinn.dbscheduler.console.ConsoleProperties;
import io.github.logicsatinn.dbscheduler.console.data.Dialect;
import io.github.logicsatinn.dbscheduler.console.data.ExecutionRepository;
import io.github.logicsatinn.dbscheduler.console.service.ExecutionActions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ActionsControllerTest {

    static final Instant NOW = Instant.parse("2026-07-13T12:00:00Z");
    static final OneTimeTask<Void> TASK = Tasks.oneTime("action-task").execute((i, c) -> {});

    SchedulerClient client;
    ExecutionRepository repo;
    ConsoleProperties props;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        DataSource ds = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .addScript("db-scheduler-schema/h2.sql")
                .build();
        client = SchedulerClient.Builder.create(ds, TASK).build();
        repo = new ExecutionRepository(ds, "scheduled_tasks", Dialect.H2);
        props = new ConsoleProperties();
        var controller = new ActionsController(props, new TemplateRenderer(),
                new ExecutionActions(client, Clock.fixed(NOW, ZoneOffset.UTC)));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .addPlaceholderValue("db-scheduler-console.base-path", "/db-scheduler-console")
                .build();
    }

    @Test
    void runNowActsAndSignalsRefresh() throws Exception {
        client.scheduleIfNotExists(TASK.instance("a"), NOW.plus(1, ChronoUnit.HOURS));
        var body = mvc.perform(post("/db-scheduler-console/actions/run-now")
                        .param("task", "action-task").param("id", "a"))
                .andExpect(status().isOk())
                .andExpect(header().string("HX-Trigger", "dsc-refresh"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("flash").contains("succeeded");
        assertThat(repo.find("action-task", "a").orElseThrow().executionTime()).isEqualTo(NOW);
    }

    @Test
    void rescheduleParsesDatetimeLocal() throws Exception {
        client.scheduleIfNotExists(TASK.instance("a"), NOW.plus(1, ChronoUnit.HOURS));
        mvc.perform(post("/db-scheduler-console/actions/reschedule")
                        .param("task", "action-task").param("id", "a")
                        .param("when", "2026-07-14T09:30"))
                .andExpect(status().isOk());
        var newTime = repo.find("action-task", "a").orElseThrow().executionTime();
        assertThat(newTime).isEqualTo(java.time.LocalDateTime.parse("2026-07-14T09:30")
                .atZone(java.time.ZoneId.systemDefault()).toInstant());
    }

    @Test
    void deleteRemovesRow() throws Exception {
        client.scheduleIfNotExists(TASK.instance("a"), NOW.plus(1, ChronoUnit.HOURS));
        mvc.perform(post("/db-scheduler-console/actions/delete")
                        .param("task", "action-task").param("id", "a"))
                .andExpect(status().isOk());
        assertThat(repo.find("action-task", "a")).isEmpty();
    }

    @Test
    void bulkAggregatesAndReportsMixedResults() throws Exception {
        client.scheduleIfNotExists(TASK.instance("a"), NOW.plus(1, ChronoUnit.HOURS));
        var body = mvc.perform(post("/db-scheduler-console/actions/bulk")
                        .param("kind", "RUN_NOW")
                        .param("ref", "action-task::a").param("ref", "action-task::missing"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("1 succeeded").contains("1 failed").contains("error");
    }

    @Test
    void readOnlyBlocksEverythingWith403() throws Exception {
        props.setReadOnly(true);
        client.scheduleIfNotExists(TASK.instance("a"), NOW.plus(1, ChronoUnit.HOURS));
        var body = mvc.perform(post("/db-scheduler-console/actions/run-now")
                        .param("task", "action-task").param("id", "a"))
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("read-only");
        assertThat(repo.find("action-task", "a").orElseThrow().executionTime())
                .isEqualTo(NOW.plus(1, ChronoUnit.HOURS)); // unchanged
    }
}
```

`CsrfRenderingTest.java`:
```java
package io.github.logicsatinn.dbscheduler.console.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.github.logicsatinn.dbscheduler.console.ConsoleProperties;
import io.github.logicsatinn.dbscheduler.console.data.Dialect;
import io.github.logicsatinn.dbscheduler.console.data.ExecutionRepository;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryRepository;
import io.github.logicsatinn.dbscheduler.console.service.StatsService;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CsrfRenderingTest {

    @Test
    void csrfTokenBecomesHxHeaders() throws Exception {
        var ds = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .addScript("db-scheduler-schema/h2.sql")
                .addScript("db-scheduler-console/migrations/h2.sql")
                .build();
        var history = new HistoryRepository(ds, Dialect.H2);
        var controller = new OverviewController(
                new PageCtxFactory(new ConsoleProperties()), new TemplateRenderer(),
                new StatsService(new ExecutionRepository(ds, "scheduled_tasks", Dialect.H2),
                        history, Clock.systemUTC()),
                history);
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .addPlaceholderValue("db-scheduler-console.base-path", "/db-scheduler-console")
                .build();

        var body = mvc.perform(get("/db-scheduler-console/overview")
                        .requestAttr(CsrfToken.class.getName(),
                                new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "token-123")))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("hx-headers").contains("X-CSRF-TOKEN").contains("token-123");

        var without = mvc.perform(get("/db-scheduler-console/overview"))
                .andReturn().getResponse().getContentAsString();
        assertThat(without).doesNotContain("hx-headers");
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :console-core:test --tests '*ActionsControllerTest' --tests '*CsrfRenderingTest'`
Expected: `ActionsControllerTest` FAILS (controller missing); `CsrfRenderingTest` should already PASS (CsrfSupport shipped in Task 10) — if it fails, fix `CsrfSupport`/layout before continuing.

- [ ] **Step 3: Implement `ActionsController`**

```java
package io.github.logicsatinn.dbscheduler.console.web;

import io.github.logicsatinn.dbscheduler.console.ConsoleProperties;
import io.github.logicsatinn.dbscheduler.console.service.ExecutionActions;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("${db-scheduler-console.base-path:/db-scheduler-console}")
public class ActionsController {

    private final ConsoleProperties props;
    private final TemplateRenderer templates;
    private final ExecutionActions actions;

    public ActionsController(ConsoleProperties props, TemplateRenderer templates,
            ExecutionActions actions) {
        this.props = props;
        this.templates = templates;
        this.actions = actions;
    }

    @PostMapping("/actions/run-now")
    public ResponseEntity<String> runNow(@RequestParam String task, @RequestParam String id) {
        if (props.isReadOnly()) {
            return readOnly();
        }
        return flash(actions.runNow(task, id));
    }

    @PostMapping("/actions/reschedule")
    public ResponseEntity<String> reschedule(@RequestParam String task, @RequestParam String id,
            @RequestParam String when) {
        if (props.isReadOnly()) {
            return readOnly();
        }
        try {
            var instant = LocalDateTime.parse(when).atZone(ZoneId.systemDefault()).toInstant();
            return flash(actions.reschedule(task, id, instant));
        } catch (DateTimeParseException e) {
            return flash(new ExecutionActions.ActionResult(false, "Invalid date/time: " + when));
        }
    }

    @PostMapping("/actions/delete")
    public ResponseEntity<String> delete(@RequestParam String task, @RequestParam String id) {
        if (props.isReadOnly()) {
            return readOnly();
        }
        return flash(actions.delete(task, id));
    }

    @PostMapping("/actions/bulk")
    public ResponseEntity<String> bulk(@RequestParam String kind,
            @RequestParam(name = "ref", required = false) List<String> refs) {
        if (props.isReadOnly()) {
            return readOnly();
        }
        if (refs == null || refs.isEmpty()) {
            return flash(new ExecutionActions.ActionResult(false, "Nothing selected"));
        }
        var parsed = refs.stream().map(ExecutionActions.InstanceRef::parse).toList();
        var actionKind = ExecutionActions.Kind.valueOf(kind);
        return flash(actions.bulk(actionKind, parsed));
    }

    private ResponseEntity<String> flash(ExecutionActions.ActionResult result) {
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("text/html;charset=UTF-8"))
                .header("HX-Trigger", "dsc-refresh")
                .body(templates.render("fragments/flash.jte", Map.of("result", result)));
    }

    private ResponseEntity<String> readOnly() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .contentType(MediaType.valueOf("text/html;charset=UTF-8"))
                .body(templates.render("fragments/flash.jte", Map.of("result",
                        new ExecutionActions.ActionResult(false,
                                "The console is in read-only mode — actions are disabled"))));
    }
}
```

- [ ] **Step 4: Write `fragments/flash.jte`**

```html
@param io.github.logicsatinn.dbscheduler.console.service.ExecutionActions.ActionResult result
<div class="flash @if(!result.success())error@endif" onclick="this.remove()" role="status">
    ${result.message()} <span class="muted">· click to dismiss</span>
</div>
```

- [ ] **Step 5: Wire actions into the executions table**

In `ExecutionsController.executions(...)` add to the model:
```java
        model.put("refreshUrl", request.getRequestURL() + queryBase(state, task, q, from, to, size)
                + "&sort=" + sortColumn.name() + "&dir=" + (desc ? "desc" : "asc")
                + "&page=" + Math.max(0, page));
```
and in `detail(...)`:
```java
        model.put("selfUrl", ctxFactory.page("executions", request).basePath() + "/execution?task="
                + java.net.URLEncoder.encode(task, java.nio.charset.StandardCharsets.UTF_8)
                + "&id=" + java.net.URLEncoder.encode(id, java.nio.charset.StandardCharsets.UTF_8));
```

In `fragments/executionsTable.jte`:
1. Add `@param String refreshUrl` and change the region opening tag to:
```html
<div id="executions-region"
     hx-get="${refreshUrl}" hx-trigger="dsc-refresh from:body"
     hx-select="#executions-region" hx-swap="outerHTML">
```
2. Insert the bulk toolbar right after the opening `<div>`:
```html
    @if(!ctx.readOnly())
        <form id="bulk-form" hx-post="${ctx.basePath()}/actions/bulk" hx-target="#flash" hx-swap="innerHTML">
            <div class="toolbar">
                <select name="kind">
                    <option value="RUN_NOW">Run now</option>
                    <option value="DELETE">Delete</option>
                </select>
                <button type="submit">Apply to selected</button>
            </div>
        </form>
    @endif
```
3. Add an Actions header `<th></th>` (last column) and a per-row cell:
```html
                <td>
                    @if(!ctx.readOnly())
                        <form style="display:inline" hx-post="${ctx.basePath()}/actions/run-now" hx-target="#flash" hx-swap="innerHTML">
                            <input type="hidden" name="task" value="${row.taskName()}">
                            <input type="hidden" name="id" value="${row.instanceId()}">
                            <button type="submit">Run now</button>
                        </form>
                        <form style="display:inline" hx-post="${ctx.basePath()}/actions/delete" hx-confirm="Delete this execution?" hx-target="#flash" hx-swap="innerHTML">
                            <input type="hidden" name="task" value="${row.taskName()}">
                            <input type="hidden" name="id" value="${row.instanceId()}">
                            <button type="submit" class="danger">Delete</button>
                        </form>
                    @endif
                </td>
```
4. Update the empty-row `colspan` from 8 to 9, and pass `refreshUrl = refreshUrl` from `pages/executions.jte`'s `@template.fragments.executionsTable(...)` call (add `@param String refreshUrl` there too and forward it).

In `pages/executionDetail.jte`: add `@param String selfUrl`, wrap the three cards in
```html
    <div hx-get="${selfUrl}" hx-trigger="dsc-refresh from:body" hx-select="#main" hx-target="#main" hx-swap="innerHTML">
```
…closing `</div>` after the last card, and add an Actions card (before History):
```html
    @if(!ctx.readOnly())
        <div class="card">
            <h2>Actions</h2>
            <form style="display:inline" hx-post="${ctx.basePath()}/actions/run-now" hx-target="#flash" hx-swap="innerHTML">
                <input type="hidden" name="task" value="${row.taskName()}">
                <input type="hidden" name="id" value="${row.instanceId()}">
                <button type="submit">Run now</button>
            </form>
            <form style="display:inline" hx-post="${ctx.basePath()}/actions/reschedule" hx-target="#flash" hx-swap="innerHTML">
                <input type="hidden" name="task" value="${row.taskName()}">
                <input type="hidden" name="id" value="${row.instanceId()}">
                <input type="datetime-local" name="when" required>
                <button type="submit">Reschedule</button>
            </form>
        </div>
    @endif
```

- [ ] **Step 6: Run the web test suite**

Run: `./gradlew :console-core:test --tests '*ActionsControllerTest' --tests '*CsrfRenderingTest' --tests '*ExecutionsControllerTest'`
Expected: PASS. (`ExecutionsControllerTest` needed no change — the new `refreshUrl`/toolbar render inside the same region.)

- [ ] **Step 7: Commit**

```bash
git add console-core
git commit -m "feat(web): admin actions with flash messages, htmx refresh, read-only 403"
```

---

### Task 16: Spring Boot 3 starter auto-configuration

**Files:**
- Create: `console-core/src/main/java/io/github/logicsatinn/dbscheduler/console/ConsoleAvailability.java`
- Create: `…/console/web/ConsoleAvailabilityInterceptor.java`
- Modify: `…/data/ExecutionRepository.java` — add `public String tableName()` getter
- Create: `console-spring-boot-3-starter/src/main/java/io/github/logicsatinn/dbscheduler/console/boot3/DbSchedulerConsoleAutoConfiguration.java`
- Create: `console-spring-boot-3-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Test: `console-spring-boot-3-starter/src/test/java/io/github/logicsatinn/dbscheduler/console/boot3/DbSchedulerConsoleAutoConfigurationTest.java`
- Test: `console-core/src/test/java/io/github/logicsatinn/dbscheduler/console/web/ConsoleAvailabilityInterceptorTest.java`

**Interfaces:**
- Consumes: everything from Tasks 2–15; db-scheduler starter's `DbSchedulerAutoConfiguration` (collects `List<Task<?>>` and `List<SchedulerListener>` beans — verified for 16.12.0), `DbSchedulerProperties.getTableName()`, `DbSchedulerCustomizer.serializer()`.
- Produces:
  - `record ConsoleAvailability(Dialect dialect)` — `boolean available()`; `Dialect dialectOrFallback()` returns `dialect` or `Dialect.H2` (inert placeholder so beans can construct; the interceptor blocks all traffic when unavailable).
  - `class ConsoleAvailabilityInterceptor implements HandlerInterceptor` — 503 + plain HTML message when unavailable.
  - `@AutoConfiguration` class activating when: `Scheduler` bean present, servlet web app, `db-scheduler-console.enabled` not false. Registers: availability, repositories (honoring `db-scheduler.table-name`), `StatsService`, `ExecutionActions`, `RecurringTasksService`, `TaskDataRenderer` (serializer from `DbSchedulerCustomizer` if provided), `TemplateRenderer`, `PageCtxFactory`, all six controllers, the interceptor, and — when `db-scheduler-console.history.enabled` — `ConsoleSchedulerListener` plus the purge `RecurringTask`.
  - Starter jar registers it via `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.

- [ ] **Step 1: Write the failing interceptor test** (in console-core)

```java
package io.github.logicsatinn.dbscheduler.console.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.logicsatinn.dbscheduler.console.ConsoleAvailability;
import io.github.logicsatinn.dbscheduler.console.data.Dialect;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ConsoleAvailabilityInterceptorTest {

    @Test
    void availablePassesThrough() throws Exception {
        var interceptor = new ConsoleAvailabilityInterceptor(new ConsoleAvailability(Dialect.POSTGRES));
        assertThat(interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), new Object()))
                .isTrue();
    }

    @Test
    void unavailableReturns503() throws Exception {
        var interceptor = new ConsoleAvailabilityInterceptor(new ConsoleAvailability(null));
        var response = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(new MockHttpServletRequest(), response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("unsupported database");
    }
}
```

- [ ] **Step 2: Implement availability pieces (console-core)**

`ConsoleAvailability.java`:
```java
package io.github.logicsatinn.dbscheduler.console;

import io.github.logicsatinn.dbscheduler.console.data.Dialect;

/** Null dialect = unsupported database: beans exist but the interceptor blocks all traffic. */
public record ConsoleAvailability(Dialect dialect) {

    public boolean available() {
        return dialect != null;
    }

    public Dialect dialectOrFallback() {
        return dialect != null ? dialect : Dialect.H2;
    }
}
```

`ConsoleAvailabilityInterceptor.java`:
```java
package io.github.logicsatinn.dbscheduler.console.web;

import io.github.logicsatinn.dbscheduler.console.ConsoleAvailability;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

public class ConsoleAvailabilityInterceptor implements HandlerInterceptor {

    private final ConsoleAvailability availability;

    public ConsoleAvailabilityInterceptor(ConsoleAvailability availability) {
        this.availability = availability;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (availability.available()) {
            return true;
        }
        response.setStatus(503);
        response.setContentType("text/html;charset=UTF-8");
        response.getWriter().write(
                "<p>db-scheduler-console is disabled: unsupported database. See the application log.</p>");
        return false;
    }
}
```

Also add to `ExecutionRepository`:
```java
    public String tableName() {
        return table;
    }
```

Run: `./gradlew :console-core:test --tests '*ConsoleAvailabilityInterceptorTest'`
Expected: PASS.

- [ ] **Step 3: Write the failing auto-configuration test** (starter module)

```java
package io.github.logicsatinn.dbscheduler.console.boot3;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.kagkarlsson.scheduler.boot.autoconfigure.DbSchedulerAutoConfiguration;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import io.github.logicsatinn.dbscheduler.console.ConsoleProperties;
import io.github.logicsatinn.dbscheduler.console.data.ExecutionRepository;
import io.github.logicsatinn.dbscheduler.console.service.ConsoleSchedulerListener;
import io.github.logicsatinn.dbscheduler.console.service.ExecutionActions;
import io.github.logicsatinn.dbscheduler.console.web.ActionsController;
import io.github.logicsatinn.dbscheduler.console.web.ExecutionsController;
import io.github.logicsatinn.dbscheduler.console.web.HistoryController;
import io.github.logicsatinn.dbscheduler.console.web.OverviewController;
import io.github.logicsatinn.dbscheduler.console.web.RecurringController;
import io.github.logicsatinn.dbscheduler.console.web.StaticAssetsController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class DbSchedulerConsoleAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    DataSourceAutoConfiguration.class,
                    DbSchedulerAutoConfiguration.class,
                    DbSchedulerConsoleAutoConfiguration.class))
            .withPropertyValues(
                    "spring.datasource.url=jdbc:h2:mem:autoconf;DB_CLOSE_DELAY=-1",
                    "spring.datasource.driver-class-name=org.h2.Driver");

    @Test
    void registersEverythingByDefault() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(OverviewController.class);
            assertThat(ctx).hasSingleBean(ExecutionsController.class);
            assertThat(ctx).hasSingleBean(RecurringController.class);
            assertThat(ctx).hasSingleBean(HistoryController.class);
            assertThat(ctx).hasSingleBean(ActionsController.class);
            assertThat(ctx).hasSingleBean(StaticAssetsController.class);
            assertThat(ctx).hasSingleBean(ExecutionActions.class);
            assertThat(ctx).hasSingleBean(ConsoleSchedulerListener.class);
            assertThat(ctx.getBeansOfType(RecurringTask.class))
                    .containsKey("dbSchedulerConsoleHistoryPurgeTask");
            assertThat(ctx.getBean(ExecutionRepository.class).tableName())
                    .isEqualTo("scheduled_tasks");
        });
    }

    @Test
    void honorsCustomTableName() {
        runner.withPropertyValues("db-scheduler.table-name=my_tasks").run(ctx ->
                assertThat(ctx.getBean(ExecutionRepository.class).tableName()).isEqualTo("my_tasks"));
    }

    @Test
    void masterSwitchDisables() {
        runner.withPropertyValues("db-scheduler-console.enabled=false").run(ctx ->
                assertThat(ctx).doesNotHaveBean(OverviewController.class));
    }

    @Test
    void historyToggleDisablesListenerAndPurge() {
        runner.withPropertyValues("db-scheduler-console.history.enabled=false").run(ctx -> {
            assertThat(ctx).doesNotHaveBean(ConsoleSchedulerListener.class);
            assertThat(ctx.getBeansOfType(RecurringTask.class)).isEmpty();
            assertThat(ctx).hasSingleBean(OverviewController.class); // live views still on
        });
    }

    @Test
    void backsOffWithoutScheduler() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DataSourceAutoConfiguration.class,
                        DbSchedulerConsoleAutoConfiguration.class))
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:noscheduler;DB_CLOSE_DELAY=-1",
                        "spring.datasource.driver-class-name=org.h2.Driver")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(OverviewController.class));
    }

    @Test
    void propertiesBind() {
        runner.withPropertyValues(
                "db-scheduler-console.read-only=true",
                "db-scheduler-console.history.retention=7d",
                "db-scheduler-console.polling-interval=10s")
              .run(ctx -> {
                  var props = ctx.getBean(ConsoleProperties.class);
                  assertThat(props.isReadOnly()).isTrue();
                  assertThat(props.getHistory().getRetention()).isEqualTo(java.time.Duration.ofDays(7));
                  assertThat(props.getPollingInterval()).isEqualTo(java.time.Duration.ofSeconds(10));
              });
    }
}
```

- [ ] **Step 4: Run to verify failure**

Run: `./gradlew :console-spring-boot-3-starter:test`
Expected: FAIL — auto-configuration class missing.

- [ ] **Step 5: Implement the auto-configuration**

```java
package io.github.logicsatinn.dbscheduler.console.boot3;

import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerCustomizer;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerProperties;
import com.github.kagkarlsson.scheduler.serializer.Serializer;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import io.github.logicsatinn.dbscheduler.console.ConsoleAvailability;
import io.github.logicsatinn.dbscheduler.console.ConsoleProperties;
import io.github.logicsatinn.dbscheduler.console.data.Dialect;
import io.github.logicsatinn.dbscheduler.console.data.ExecutionRepository;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryRepository;
import io.github.logicsatinn.dbscheduler.console.service.ConsoleSchedulerListener;
import io.github.logicsatinn.dbscheduler.console.service.ExecutionActions;
import io.github.logicsatinn.dbscheduler.console.service.HistoryPurgeTask;
import io.github.logicsatinn.dbscheduler.console.service.RecurringTasksService;
import io.github.logicsatinn.dbscheduler.console.service.StatsService;
import io.github.logicsatinn.dbscheduler.console.service.TaskDataRenderer;
import io.github.logicsatinn.dbscheduler.console.web.ActionsController;
import io.github.logicsatinn.dbscheduler.console.web.ConsoleAvailabilityInterceptor;
import io.github.logicsatinn.dbscheduler.console.web.ExecutionsController;
import io.github.logicsatinn.dbscheduler.console.web.HistoryController;
import io.github.logicsatinn.dbscheduler.console.web.OverviewController;
import io.github.logicsatinn.dbscheduler.console.web.PageCtxFactory;
import io.github.logicsatinn.dbscheduler.console.web.RecurringController;
import io.github.logicsatinn.dbscheduler.console.web.StaticAssetsController;
import io.github.logicsatinn.dbscheduler.console.web.TemplateRenderer;
import java.time.Clock;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@AutoConfiguration(
        afterName = "com.github.kagkarlsson.scheduler.boot.autoconfigure.DbSchedulerAutoConfiguration")
@ConditionalOnClass(Scheduler.class)
@ConditionalOnBean(Scheduler.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "db-scheduler-console", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ConsoleProperties.class)
public class DbSchedulerConsoleAutoConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(DbSchedulerConsoleAutoConfiguration.class);

    private static final Clock CLOCK = Clock.systemUTC();

    @Bean
    ConsoleAvailability dbSchedulerConsoleAvailability(DataSource dataSource) {
        var dialect = Dialect.fromDataSource(dataSource).orElse(null);
        if (dialect == null) {
            LOG.error("db-scheduler-console: unsupported database — the dashboard is disabled."
                    + " Supported: PostgreSQL, MySQL, MariaDB, SQL Server, Oracle, H2.");
        }
        return new ConsoleAvailability(dialect);
    }

    @Bean
    ExecutionRepository dbSchedulerConsoleExecutionRepository(DataSource dataSource,
            DbSchedulerProperties dbSchedulerProperties, ConsoleAvailability availability) {
        return new ExecutionRepository(dataSource,
                dbSchedulerProperties.getTableName(), availability.dialectOrFallback());
    }

    @Bean
    HistoryRepository dbSchedulerConsoleHistoryRepository(DataSource dataSource,
            ConsoleAvailability availability) {
        return new HistoryRepository(dataSource, availability.dialectOrFallback());
    }

    @Bean
    StatsService dbSchedulerConsoleStatsService(ExecutionRepository executions,
            HistoryRepository history) {
        return new StatsService(executions, history, CLOCK);
    }

    @Bean
    ExecutionActions dbSchedulerConsoleExecutionActions(Scheduler scheduler) {
        return new ExecutionActions(scheduler, CLOCK);
    }

    @Bean
    RecurringTasksService dbSchedulerConsoleRecurringTasksService(List<Task<?>> tasks,
            ExecutionRepository executions, HistoryRepository history) {
        return new RecurringTasksService(tasks, executions, history);
    }

    @Bean
    TaskDataRenderer dbSchedulerConsoleTaskDataRenderer(ConsoleProperties props,
            ObjectProvider<DbSchedulerCustomizer> customizer) {
        Serializer serializer = customizer.stream()
                .flatMap(c -> c.serializer().stream())
                .findFirst().orElse(null);
        return new TaskDataRenderer(serializer, props.getTaskData().isVisible());
    }

    @Bean
    @ConditionalOnProperty(prefix = "db-scheduler-console.history", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    ConsoleSchedulerListener dbSchedulerConsoleSchedulerListener(HistoryRepository history) {
        return new ConsoleSchedulerListener(history);
    }

    @Bean
    @ConditionalOnProperty(prefix = "db-scheduler-console.history", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    RecurringTask<Void> dbSchedulerConsoleHistoryPurgeTask(HistoryRepository history,
            ConsoleProperties props) {
        return HistoryPurgeTask.create(history, props.getHistory().getRetention(), CLOCK);
    }

    @Bean
    TemplateRenderer dbSchedulerConsoleTemplateRenderer() {
        return new TemplateRenderer();
    }

    @Bean
    PageCtxFactory dbSchedulerConsolePageCtxFactory(ConsoleProperties props) {
        return new PageCtxFactory(props);
    }

    @Bean
    OverviewController dbSchedulerConsoleOverviewController(PageCtxFactory ctx,
            TemplateRenderer templates, StatsService stats, HistoryRepository history) {
        return new OverviewController(ctx, templates, stats, history);
    }

    @Bean
    ExecutionsController dbSchedulerConsoleExecutionsController(PageCtxFactory ctx,
            TemplateRenderer templates, ExecutionRepository executions, HistoryRepository history,
            TaskDataRenderer taskData, StatsService stats) {
        return new ExecutionsController(ctx, templates, executions, history, taskData, stats, CLOCK);
    }

    @Bean
    RecurringController dbSchedulerConsoleRecurringController(PageCtxFactory ctx,
            TemplateRenderer templates, RecurringTasksService service) {
        return new RecurringController(ctx, templates, service);
    }

    @Bean
    HistoryController dbSchedulerConsoleHistoryController(PageCtxFactory ctx,
            TemplateRenderer templates, HistoryRepository history, ConsoleAvailability availability) {
        return new HistoryController(ctx, templates, history, availability.dialectOrFallback());
    }

    @Bean
    ActionsController dbSchedulerConsoleActionsController(ConsoleProperties props,
            TemplateRenderer templates, ExecutionActions actions) {
        return new ActionsController(props, templates, actions);
    }

    @Bean
    StaticAssetsController dbSchedulerConsoleStaticAssetsController() {
        return new StaticAssetsController();
    }

    @Bean
    WebMvcConfigurer dbSchedulerConsoleWebMvcConfigurer(ConsoleAvailability availability,
            ConsoleProperties props) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new ConsoleAvailabilityInterceptor(availability))
                        .addPathPatterns(props.getBasePath() + "/**");
            }
        };
    }
}
```

- [ ] **Step 6: Write the imports file**

`console-spring-boot-3-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:
```
io.github.logicsatinn.dbscheduler.console.boot3.DbSchedulerConsoleAutoConfiguration
```

- [ ] **Step 7: Run to verify pass**

Run: `./gradlew :console-spring-boot-3-starter:test`
Expected: PASS (6 tests).

- [ ] **Step 8: Commit**

```bash
git add console-core console-spring-boot-3-starter
git commit -m "feat(starter): Spring Boot 3 auto-configuration with availability guard"
```

---

### Task 17: Spring Boot 4 starter

**Files:**
- Create: `console-spring-boot-4-starter/src/main/java/io/github/logicsatinn/dbscheduler/console/boot4/DbSchedulerConsoleAutoConfiguration.java` — byte-for-byte identical to the Boot 3 class from Task 16 except the package declaration: `package io.github.logicsatinn.dbscheduler.console.boot4;`
- Create: `console-spring-boot-4-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` containing `io.github.logicsatinn.dbscheduler.console.boot4.DbSchedulerConsoleAutoConfiguration`
- Test: `console-spring-boot-4-starter/src/test/java/io/github/logicsatinn/dbscheduler/console/boot4/DbSchedulerConsoleAutoConfigurationTest.java`

**Interfaces:**
- Consumes: `com.github.kagkarlsson:db-scheduler-spring-boot-4-starter:16.12.0` (same `com.github.kagkarlsson.scheduler.boot.autoconfigure.DbSchedulerAutoConfiguration` package as Boot 3) and Spring Boot **4.0.7**.
- Produces: the Boot 4 starter artifact. Boot 4 relocation note: `DataSourceAutoConfiguration` lives at `org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration` in Boot 4 (it moved out of `org.springframework.boot.autoconfigure.jdbc`). If other Boot-4 relocations bite (e.g. `@ConditionalOnWebApplication` stays put, but test utilities moved), fix imports per compiler errors — the class bodies stay identical.

- [ ] **Step 1: Copy the auto-configuration class and imports file** (package `boot4` as above).

- [ ] **Step 2: Write the test** — same six test methods as Task 16's `DbSchedulerConsoleAutoConfigurationTest`, with two changes: package `io.github.logicsatinn.dbscheduler.console.boot4`, and the Boot 4 import:

```java
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
```

(Copy the file from the boot3 starter test, adjust those two lines. If `WebApplicationContextRunner`'s package differs in Boot 4, follow the compiler.)

- [ ] **Step 3: Run to verify pass**

Run: `./gradlew :console-spring-boot-4-starter:test`
Expected: PASS (6 tests). This is the compatibility proof that console-core (compiled against Spring Framework 6) runs on Boot 4 / Framework 7.

- [ ] **Step 4: Run the whole build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL — all modules compile, all non-container tests pass.

- [ ] **Step 5: Commit**

```bash
git add console-spring-boot-4-starter
git commit -m "feat(starter): Spring Boot 4 auto-configuration"
```

---

### Task 18: Example apps (Boot 3 + Boot 4) with demo tasks

**Files:**
- Create: `examples/boot3-example/src/main/java/io/github/logicsatinn/dbscheduler/console/example/boot3/DemoApp.java`, `DemoTasks.java`
- Create: `examples/boot3-example/src/main/resources/application.properties`, `schema.sql`
- Test: `examples/boot3-example/src/test/java/io/github/logicsatinn/dbscheduler/console/example/boot3/DemoAppSmokeTest.java`
- Create: the same four files + test under `examples/boot4-example/…/example/boot4/` (identical content except package `…example.boot4`)

**Interfaces:**
- Consumes: the starters (Tasks 16–17).
- Produces: runnable demo apps (`./gradlew :examples:boot3-example:run`) and CI smoke tests. These are also the **manual eyeball gate** for the UI.

- [ ] **Step 1: Write `DemoApp.java`**

```java
package io.github.logicsatinn.dbscheduler.console.example.boot3;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class DemoApp {
    public static void main(String[] args) {
        SpringApplication.run(DemoApp.class, args);
    }
}
```

- [ ] **Step 2: Write `DemoTasks.java`**

```java
package io.github.logicsatinn.dbscheduler.console.example.boot3;

import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DemoTasks {

    @Bean
    Task<Void> steadyTask() {
        return Tasks.recurring("demo-steady", FixedDelay.ofSeconds(20)).execute((inst, ctx) -> {});
    }

    @Bean
    Task<Void> slowTask() {
        return Tasks.recurring("demo-slow", FixedDelay.ofMinutes(1)).execute((inst, ctx) -> {
            try {
                Thread.sleep(8000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    @Bean
    Task<Void> flakyTask() {
        return Tasks.recurring("demo-flaky", FixedDelay.ofSeconds(45)).execute((inst, ctx) -> {
            if (ThreadLocalRandom.current().nextBoolean()) {
                throw new IllegalStateException("Flaky demo failure — this is intentional");
            }
        });
    }

    @Bean
    OneTimeTask<Void> emailTask() {
        return Tasks.oneTime("demo-email").execute((inst, ctx) -> {});
    }

    @Bean
    CommandLineRunner seedDemoData(Scheduler scheduler, OneTimeTask<Void> emailTask) {
        return args -> {
            for (int i = 1; i <= 5; i++) {
                scheduler.scheduleIfNotExists(
                        emailTask.instance("order-" + i), Instant.now().plusSeconds(3600L * i));
            }
        };
    }
}
```

- [ ] **Step 3: Write `application.properties` and `schema.sql`**

`application.properties`:
```properties
spring.datasource.url=jdbc:h2:mem:demo;DB_CLOSE_DELAY=-1
spring.datasource.driver-class-name=org.h2.Driver
spring.sql.init.schema-locations=classpath:schema.sql
db-scheduler.polling-interval=1s
db-scheduler-console.history.retention=7d
```

`schema.sql` — exact concatenation of the two H2 scripts already written in this plan: the `scheduled_tasks` DDL (Task 3 Step 1) followed by the `dsc_execution_history` DDL + its three indexes (Task 4 Step 1, `h2.sql`).

- [ ] **Step 4: Write the smoke test** (uses `local.server.port` property + JDK HttpClient — identical source works on Boot 3 and Boot 4, no relocated-class imports)

```java
package io.github.logicsatinn.dbscheduler.console.example.boot3;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DemoAppSmokeTest {

    @Value("${local.server.port}")
    int port;

    final HttpClient http = HttpClient.newHttpClient();

    String get(String path) throws Exception {
        var response = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("GET %s", path).isEqualTo(200);
        return response.body();
    }

    @Test
    void allPagesServe() throws Exception {
        assertThat(get("/db-scheduler-console/overview")).contains("db-scheduler-console");
        assertThat(get("/db-scheduler-console/executions")).contains("Executions");
        assertThat(get("/db-scheduler-console/recurring")).contains("demo-steady");
        assertThat(get("/db-scheduler-console/history")).contains("history");
        assertThat(get("/db-scheduler-console/static/htmx.min.js")).contains("htmx");
    }
}
```

- [ ] **Step 5: Run the Boot 3 smoke test**

Run: `./gradlew :examples:boot3-example:test`
Expected: PASS.

- [ ] **Step 6: Mirror everything into `examples/boot4-example`** — identical file contents, package `io.github.logicsatinn.dbscheduler.console.example.boot4`, and in the smoke test note: on Boot 4 `@SpringBootTest`'s `webEnvironment` import is unchanged. Run: `./gradlew :examples:boot4-example:test` — Expected: PASS.

- [ ] **Step 7: Manual eyeball gate (required — charts and layout can't be fully asserted in tests)**

Run: `./gradlew :examples:boot3-example:run`
Open http://localhost:8080/db-scheduler-console and verify, in both light and dark OS themes:
- Overview: tiles populate, chart bars appear within ~a minute of demo activity (green = succeeded, red = failed segments, legend labels), recent failures list fills as `demo-flaky` fails, everything updates without page reloads (5s polling).
- Executions: filters narrow the table, URL updates as filters change, Run now / Delete / bulk actions produce a flash and refresh the table.
- Execution detail: task data, history timeline with expandable stack traces, Run now + Reschedule work.
- Recurring: all four demo tasks listed with next run + last outcome.
- History: filters work; stacktrace expands.
- No label collisions or overflow in the chart; no horizontal page scroll.
Stop the app with Ctrl-C.

- [ ] **Step 8: Commit**

```bash
git add examples
git commit -m "feat(examples): Boot 3 and Boot 4 demo apps with seeded demo tasks"
```

---

### Task 19: CI, README, license

**Files:**
- Create: `.github/workflows/build.yml`
- Create: `README.md`
- Create: `LICENSE`

**Interfaces:**
- Consumes: the `containerTest` Gradle task (Task 5), the whole build.
- Produces: CI on push/PR (fast job always; dialect matrix job on ubuntu with Docker), user-facing documentation.

- [ ] **Step 1: Write `.github/workflows/build.yml`**

```yaml
name: build

on:
  push:
    branches: [main]
  pull_request:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "17"
      - uses: gradle/actions/setup-gradle@v4
      - run: ./gradlew build

  dialect-matrix:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "17"
      - uses: gradle/actions/setup-gradle@v4
      - run: ./gradlew :console-core:containerTest
```

- [ ] **Step 2: Add the Apache 2.0 license**

Run: `curl -fsSL https://www.apache.org/licenses/LICENSE-2.0.txt -o LICENSE`
Expected: `LICENSE` file starts with "Apache License, Version 2.0".

- [ ] **Step 3: Write `README.md`**

```markdown
# db-scheduler-console

A dashboard for [db-scheduler](https://github.com/kagkarlsson/db-scheduler), inspired by
JobRunr's UI. Server-rendered with [JTE](https://jte.gg) + [htmx](https://htmx.org) — no
React, no Node build, no CDN calls. Add one starter dependency and open
`/db-scheduler-console`.

**Views:** overview (stat tiles, 24h throughput chart, recent failures) · executions
(filter/search/sort/paginate, run-now, reschedule, delete, bulk) · execution detail
(task data, per-instance history, stack traces) · recurring task definitions ·
searchable execution history.

## Quickstart

Spring Boot 3:
​```kotlin
implementation("io.github.logicsatinn:console-spring-boot-3-starter:0.1.0-SNAPSHOT")
​```

Spring Boot 4:
​```kotlin
implementation("io.github.logicsatinn:console-spring-boot-4-starter:0.1.0-SNAPSHOT")
​```

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

​```java
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
​```

CSRF: if Spring Security CSRF protection is enabled, the console sends the token
automatically on every action (htmx `hx-headers`). Don't disable CSRF for it.

## Development

​```bash
./gradlew build                              # compile + fast tests (H2)
./gradlew :console-core:containerTest        # dialect matrix (needs Docker)
./gradlew :examples:boot3-example:run        # demo app on :8080
​```
```
(Remove the zero-width escapes around the inner code fences when writing the real file — they are only there so this plan's markdown renders.)

- [ ] **Step 4: Full verification**

Run: `./gradlew build && ./gradlew :examples:boot3-example:test :examples:boot4-example:test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add .github README.md LICENSE
git commit -m "docs: README, Apache-2.0 license, CI workflow"
```

---

## Execution notes

- Tasks are strictly ordered 1→19; each ends with a green test run and a commit.
- Docker is required only for Task 5 and the CI dialect-matrix job.
- Two intentionally-flagged uncertainty points (both resolve at compile time, contracts unchanged): the `Execution` test constructor arity (Task 6) and Boot 4 class relocations in tests (Tasks 17–18).
- The manual UI gate is Task 18 Step 7 — do not skip it; it is the only step that looks at the rendered chart.
