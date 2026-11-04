# PowerBank: a banking system on Temporal

[![CI](https://github.com/MikaSchulz/PowerBank/actions/workflows/ci.yml/badge.svg)](https://github.com/MikaSchulz/PowerBank/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

Accounts, transfers and (soon) standing orders, running entirely locally. **The ledger is the source of truth**,
Temporal orchestrates everything that takes time or crosses system boundaries, and **fmodel** makes the business decisions.

**Stack:** Kotlin 2.4, Spring Boot 4.1, Temporal Java SDK 1.40 with the Spring Boot starter, fmodel 3.7, Postgres 18,
OpenTelemetry with Grafana LGTM, Gradle 9.

## What it does

- **Accounts and transfers:** internal transfers, transfers to an external bank as a saga with refunds, cash
  deposits; duplicate submissions are rejected, concurrent transfers never overdraw an account.
- **Suspected fraud:** transfers above a limit wait for an approval (Temporal update) for up to 3 days.
- **Double-entry ledger:** immutable postings; every transfer sums up to zero, enforced by the database.
- **End-to-end telemetry:** one trace from the click in the browser through Temporal into the SQL, metrics, logs,
  dashboards and alerts for the app, the Temporal server and Postgres. No personal data in any of it.
- **UI** to click through everything, including failure scenarios and an external bank simulator.

## Quick start

Prerequisites: Java 25 and Docker.

```bash
docker compose up -d --wait
```

```bash
./gradlew :bank-service:bootRun
```

UI: http://localhost:8080 · Temporal UI: http://localhost:8233 · Grafana: http://localhost:3000

```bash
docker compose down
```

Tests: `./gradlew test` needs no containers; `./gradlew integrationTest` runs against the stack started above.

**No Java at hand?** The [showcase](docs/showcase.md) runs everything in Docker, with demo traffic and a ten-minute
tour: `docker compose --profile showcase up -d --build --wait`.

## Documentation

| | |
| --- | --- |
| [Showcase](docs/showcase.md) | everything in Docker with demo traffic, and a ten-minute tour |
| [Local setup and tests](docs/local-setup.md) | the local stack, data and reset, unit and integration tests |
| [Using the bank](docs/usage.md) | the UI, a manual test checklist, the API with curl |
| [Architecture](docs/architecture.md) | modules, how a transfer works, the ledger, architecture rules |
| [Observability](docs/observability.md) | traces, metrics, logs, browser telemetry, dashboards and alerts |
| [Decisions](docs/decisions/README.md) | why it is built the way it is (architecture decision records) |
| [Roadmap](docs/roadmap.md) | the stages of the plan; open work is in [GitHub issues](https://github.com/MikaSchulz/PowerBank/issues) |
| [Learnings](docs/learnings.md) | deviations from the original plan and known learning points |

## Contributing

One feature per branch and pull request, merged with a merge commit; details, rules and the dependency process in
[CONTRIBUTING.md](CONTRIBUTING.md).

## License

[MIT](LICENSE)
