# Implementation notes

Deviations from `docs/superpowers/plans/2026-07-13-db-scheduler-console.md`, with justification.

## 1. `testRuntimeOnly("org.junit.platform:junit-platform-launcher")` added to every module that has tests

**Plan text:** Task 1's build files (Steps 5–8) list no `junit-platform-launcher` dependency.

**Why it was necessary:** Gradle 8.14.3 embeds an older `junit-platform-launcher` than the
JUnit Platform (1.12.x) that the Spring Boot 3.5.16 / 4.0.7 BOMs bring in via
`spring-boot-starter-test`. Running any test without an explicit launcher on the test runtime
classpath fails at discovery time with:

```
org.junit.platform.commons.JUnitException: TestEngine with ID 'junit-jupiter' failed to discover tests
Caused by: OutputDirectoryProvider not available; probably due to unaligned versions of the
junit-platform-engine and junit-platform-launcher jars on the classpath/module path.
```

Adding `testRuntimeOnly("org.junit.platform:junit-platform-launcher")` (version managed by the
Boot BOM already declared in each module) is the fix documented by both Gradle and Spring Boot.
It changes no production dependency and no published artifact.
