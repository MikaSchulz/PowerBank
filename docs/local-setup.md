# Local setup and tests

Prerequisites: Java 25 and Docker. Gradle comes with the wrapper.

## Local stack

You start and stop the local stack from `compose.yaml` (Postgres, the Temporal dev server and Grafana LGTM)
yourself; neither the app nor the tests start containers:

```bash
docker compose up -d --wait
```

```bash
./gradlew :bank-service:bootRun
```

```bash
docker compose down
```

`./gradlew composeUp` and `./gradlew composeDown` do the same (`composeDown` also stops the [showcase](showcase.md),
which runs the bank-service in Docker with demo traffic). `--wait` returns once Postgres and Temporal accept
connections. If the stack isn't running, the app stops right at startup with "Postgres is not reachable … Start
the local stack first". The connections are set in `application.yml` (`localhost:5432`, `localhost:7233`,
OTLP to `localhost:4318`); other environments override them, e.g. with `SPRING_DATASOURCE_URL`.

While it runs:

- UI: http://localhost:8080
- API: http://localhost:8080/api
- Temporal Web UI: http://localhost:8233
- Grafana (traces, metrics, logs): http://localhost:3000

## A second bank

The same bank-service also runs as a second, separate bank (bank B, "Partner Bank", #55). It has its own identity
(name, BIC, bank code in every IBAN), its own Postgres schema (`bank_b`) and its own Temporal namespace (`bank-b`),
next to bank A in the same containers. Its settings are in `application-bank-b.yml`:

```bash
./gradlew :bank-service:bootRun --args='--spring.profiles.active=bank-b'
```

or as a container: `docker compose --profile bank-b up -d --build --wait`. UI: http://localhost:8081. The two banks
don't see each other's accounts or workflows; payments between them come with the clearing house (#58).

## Data and reset

Ledger and workflows are kept on Docker volumes (`postgres-data`, `temporal-data`): running transfers survive a
restart of the containers and `docker compose down`. To start from scratch, reset both together, never only one
of them, otherwise the ledger has transfers without workflows (or the other way round):

```bash
docker compose down -v
```

Telemetry (Grafana LGTM) has no volume and starts empty after the container is recreated.

## Tests

Tests come in two kinds:

| Task | Needs | What |
| --- | --- | --- |
| `./gradlew test` | nothing, no containers | domain (deciders, risk rules), `TransferWorkflowTest` with Temporal's in-memory test environment, replay of recorded workflow histories, ArchUnit, web slices, Grafana files |
| `./gradlew integrationTest` | the running stack (`docker compose up -d --wait`) | ledger, REST API end to end and trace propagation, against real Postgres and the Temporal dev server |

```bash
./gradlew test
```

```bash
./gradlew integrationTest
```

`integrationTest` stops right away with "Postgres (localhost:5432), Temporal (localhost:7233) not reachable" if the
stack isn't running. It uses its own Postgres schema (`integration_test`) and Temporal namespace (`integration-test`),
so it doesn't mix with the data of the app you click through. All integration tests share one Spring context
(`@IntegrationTest`): with a real Temporal server, a second context would poll the same task queue and take over
the other's workflows. `./gradlew build` only runs the unit tests.

Without Java 25, Java 21 works too: `./gradlew -PjavaVersion=21 test`. CI (GitHub Actions) runs the unit tests, then
starts Postgres and Temporal with `docker compose up -d --wait` and runs the integration tests, on Java 25.
