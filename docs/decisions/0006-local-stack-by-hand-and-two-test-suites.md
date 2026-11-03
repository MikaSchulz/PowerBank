# 0006: Local stack by hand, two test suites

**Status:** accepted, 2026-11

## Context
Containers that the app or the tests start on their own are hard to control: they keep running, use other data
than expected, and hide what a test really needs. Ledger and running workflows must also survive restarts together.

## Decision
- The local stack from `compose.yaml` (Postgres, Temporal dev server, Grafana LGTM) is started and stopped by hand:
  `docker compose up -d --wait` / `docker compose down`. The app's connections are explicit in `application.yml`;
  without the stack it stops with a clear message.
- Ledger and workflows live on Docker volumes and are reset together (`docker compose down -v`), never one alone.
- **`./gradlew test`** needs no containers: domain, workflow with Temporal's in-memory environment, ArchUnit, web
  slices.
- **`./gradlew integrationTest`** runs against the running stack, in its own Postgres schema and Temporal namespace,
  with one shared Spring context. CI starts Postgres and Temporal with Docker Compose for it. Testcontainers isn't used.

## Consequences
- What runs is always visible in `docker compose ps`, and integration tests don't touch the data you click through.
- Running `integrationTest` without the stack fails fast with a hint instead of starting containers.
- Images are pinned in `compose.yaml` and updated through Dependabot pull requests.
