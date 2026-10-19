# Unreleased security hardening

This pre-release update changes integration defaults and pagination APIs.

- Read-only mode now defaults to true. Explicitly opt into mutations after configuring host auth/CSRF.
- Payload visibility and history payload retention independently default to false.
- Numbered pages and `page`/`total` Java APIs are replaced by cursor slices. URLs use `cursor`;
  size stays bounded at 200. Filters/sorting reset traversal. No snapshot consistency is promised.
- GET rendering no longer calls the scheduler serializer. Explicit trusted formatters replace that
  behavior. Payloads load only when expanded through `/payload/execution` and `/payload/history`.
- Default Java requeue requires a host deserialization filter. Review full DTO graphs and resource
  limits; the console never changes JVM-wide policy. Other custom serializers remain host-owned.
- POST clients must send `HX-Request: true` in addition to host auth/CSRF. Explicit cross-origin
  requests are rejected. Configure the external origin when proxy request metadata differs.
- Bulk requests exceeding 200 or containing invalid references are rejected before any mutation.
- Dynamic responses prohibit caching and framing by default. Host embedding is explicit opt-in.
- htmx snapshots are disabled; deploy targeted old-snapshot cleanup to host logout/rollout pages.
- Examples require supplied credentials, bind locally and deny unmatched routes. Their security
  matchers now follow the configured base path, including its root.

## Migration

Review the README security sections and existing host rules. Hiding payloads does not redact stack
traces, delete retained bytes or stop backups. Disabling history also disables purging: plan deletion
of existing history separately. Apply the database-specific additive index script under
`db-scheduler-console/migrations/upgrade/0.1.0-M2-security/` for existing M2 schemas. Fresh schemas
already have equivalent indexes. Review scheduler-table indexes and query plans with your DBA.

## Verification

Run `./gradlew build` for compilation, fast H2 tests, Boot 3/4 integration tests and coverage gates.
Run `./gradlew :console-core:containerTest` against all dialects before production rollout.
Verify custom base paths, viewer/admin rules, host CSRF, proxy origins, payload permissions,
logout/back navigation and the documented CSP in the deployed authentication environment.

### Optional browser regression

Install Playwright in a temporary test-tool directory (not as an application dependency), then
start the Boot 3 example with supplied local test credentials, port 18081, read-only off and payload
visibility on. With the same credentials in your environment, run:

```sh
npm install --prefix /tmp/dbsc-browser playwright
NODE_PATH=/tmp/dbsc-browser/node_modules node console-core/src/test/js/security-browser.cjs
```

Use `BROWSER_EXECUTABLE` to select an installed Chromium-family executable or install Playwright's
Chromium. `CONSOLE_TEST_ORIGIN` overrides the default loopback URL. The test uses a new, isolated
browser context. It applies the documented CSP, checks lazy requests and polling, exercises an
action and flash dismissal, preserves an unrelated synthetic snapshot, and simulates authorization
revocation before back navigation. It does not replace testing your real host logout flow.
