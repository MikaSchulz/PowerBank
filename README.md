# PowerBank: a banking system on Temporal

[![CI](https://github.com/MikaSchulz/PowerBank/actions/workflows/ci.yml/badge.svg)](https://github.com/MikaSchulz/PowerBank/actions/workflows/ci.yml)

Accounts, transfers and (soon) standing orders, running entirely locally. **The ledger is the source of truth**,
Temporal orchestrates everything that takes time or crosses system boundaries, and **fmodel** makes the business decisions.

**Stack:** Kotlin 2.3, Spring Boot 4.1, Temporal Java SDK 1.40 with the Spring Boot starter, fmodel 3.7, Postgres 18, Gradle 9.

## Getting started

Prerequisites: Java 25 and Docker. Gradle comes with the wrapper.

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

`./gradlew composeUp` and `./gradlew composeDown` do the same. `--wait` returns once Postgres and Temporal accept
connections. If the stack isn't running, the app stops right at startup with "Postgres is not reachable … Start
the local stack first". The connections are set in `application.yml` (`localhost:5432`, `localhost:7233`,
OTLP to `localhost:4318`); other environments override them, e.g. with `SPRING_DATASOURCE_URL`.

Ledger and workflows are kept on Docker volumes (`postgres-data`, `temporal-data`): running transfers survive a
restart of the containers and `docker compose down`. To start from scratch, reset both together, never only one
of them, otherwise the ledger has transfers without workflows (or the other way round):

```bash
docker compose down -v
```

Telemetry (Grafana LGTM) has no volume and starts empty after the container is recreated.

- UI: http://localhost:8080
- API: http://localhost:8080/api
- Temporal Web UI: http://localhost:8233
- Grafana (traces, metrics, logs): http://localhost:3000

Tests come in two kinds:

| Task | Needs | What |
| --- | --- | --- |
| `./gradlew test` | nothing, no containers | domain (decider, risk rules), `TransferWorkflowTest` with Temporal's in-memory test environment, ArchUnit, web slices, Grafana files |
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

## UI

Open **http://localhost:8080** once the app is running. The UI is a plain HTML/JS page served by the bank-service
(`bank-service/src/main/resources/static`), no build step.

- **Accounts:** open accounts, deposit, block and unblock, see every posting with the running balance.
  Nicknames like "Alice" only live in your browser (localStorage): the bank itself only knows pseudonymous IDs.
- **Transfers:** internal, to an external bank, or as a cash deposit. Each one opens a drawer with the live workflow
  status, an explanation of what Temporal is doing right now, the postings it created and a link to the Temporal UI.
- **Approvals:** transfers above the limit show up in a banner; approve or deny them via the `approve` update.
- **Scenarios:** one click each: above the approval limit, insufficient funds, external bank rejects (saga refund),
  duplicate submission (409), and a burst of 10 concurrent transfers that shows the row locks at work.
- **Simulator:** change the external bank's rejection rate, technical failure rate and delay at runtime.
  "Bank down" makes every call fail, so you can watch Temporal retry and, after 10 minutes, refund.
- **Ledger health:** the header shows the sum of all postings. With double-entry bookkeeping it must always be zero.

### Manual test checklist

1. **Quick start** creates Alice (1,000 €, 200 € overdraft) and Bob (250 €).
2. Send a normal internal transfer and watch it go from *Received* to *Booked*.
3. Run every scenario once and check the result in the drawer and the ledger health badge.
4. Open the simulator, choose **Bank down**, send an external transfer, then switch back to **Default**:
   the pending retries succeed without losing anything.
5. Kill the app (`Ctrl+C`) while an external transfer is *At external bank*, start it again:
   the workflow continues where it stopped.
6. Block Alice, try to send money from her account (rejected), unblock her again.

## Observability

Traces, metrics and logs go via OpenTelemetry (OTLP) to the `grafana/otel-lgtm` container: Grafana with Tempo,
Prometheus and Loki. The bank-service sends OTLP/HTTP to `localhost:4318` (`application.yml`).
Open Grafana at http://localhost:3000 → *Explore*.

**Traces (Tempo):** one trace per user action, from the click in the browser through Temporal into the database:

```
send transfer                                        ← bank-ui: the click
└─ HTTP POST                                         ← bank-ui: the fetch call
   └─ http post /api/transfers                       ← bank-service from here on
      ├─ query  INSERT INTO transfers …
      └─ StartWorkflow:TransferWorkflow
         └─ RunWorkflow:TransferWorkflow
            ├─ StartActivity:RiskAssess → RunActivity:RiskAssess → query …
            └─ StartActivity:LedgerBookInternalTransfer → RunActivity:LedgerBookInternalTransfer
               ├─ bank.ledger.lock      SELECT … FOR UPDATE   ← waiting for the row lock is visible here
               ├─ bank.account.command  command=Debit         ← the fmodel aggregate: load postings, decide, save
               └─ bank.account.command  command=Credit
```

- The Temporal spans come from the starter's tracing interceptors (`temporal-opentracing` plus the OpenTelemetry
  OpenTracing shim). They are replay-safe: no duplicate spans when Temporal replays a workflow.
- JDBC spans come from `datasource-micrometer`. SQL is recorded, bound values are not.
- `bank.ledger.lock` and `bank.account.command` are Micrometer observations in the `Ledger` (span and timer at once).
  Run the burst scenario and look at the lock waits.

**W3C trace context (`traceparent`):** one trace ID from the click in the browser down to the SQL.

- Every user action in the UI is the root span of a new trace; each API call in it is a client span whose
  `traceparent` (`00-<trace-id>-<span-id>-01`, sampled) the backend continues. Its polling sends the flag `00`
  (not sampled), so Tempo only contains what you actually did.
- Spring Boot continues an incoming `traceparent`; without one it starts a new trace.
- Temporal carries the context in the workflow header `_tracer-data` as a W3C `traceparent`, so the workflow and all
  its activities continue the same trace, also after a worker restart.
- The trace ID is stored with the transfer (`transfers.trace_id`), returned by `POST /api/transfers` and listed by
  `GET /api/transfers`. The transfer drawer in the UI links straight to the trace in Grafana.
- Only `/api/` requests are traced; the UI's static files, actuator calls and the browser telemetry proxy
  (`/api/otel/`) are skipped (`ObservabilityConfig`).
- `TracePropagationTest` proves all of this with an in-memory span exporter.

**Search traces (TraceQL in Grafana → Explore → Tempo):** spans carry pseudonymous business IDs, never names or IBANs.

| Find | TraceQL |
| --- | --- |
| one transfer | `{ span.bank.transfer.id = "<transfer-id>" }` or `{ span.workflowId = "transfer-<transfer-id>" }` |
| everything that touched an account | `{ span.bank.account.id = "<account-id>" }` (ledger commands) |
| transfers from or to an account | `{ span.bank.debtor.account.id = "<id>" \|\| span.bank.creditor.account.id = "<id>" }` |
| rejections and failures at the external bank | `{ name = "bank.external-bank.call" && span.outcome != "accepted" }` |
| slow lock waits | `{ name = "bank.ledger.lock" && duration > 50ms }` |

The account drawer in the UI links to the account's traces. The external bank call is its own span
(`bank.external-bank.call`) with `outcome` (accepted, rejected, failure) and `temporal.activity.attempt`; its timer
`bank_external_bank_call_milliseconds_*{outcome}` counts outcomes in Prometheus.
- Try it with curl: `-H 'traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01'`, then search for
  that trace ID in Grafana.

**Browser (`service_name="bank-ui"`):** the UI runs the OpenTelemetry JS SDK (`static/telemetry.js`) and sends
traces, logs and metrics as OTLP/HTTP to the bank-service, which forwards them unchanged to the collector
(`POST /api/otel/v1/{traces,logs,metrics}`, `BrowserTelemetryController`). The browser can't reach the collector
itself (other port, CORS), and in the cloud the proxy is where a login will be required (#36).

| What | Where |
| --- | --- |
| user actions (`send transfer`, `approve transfer`, `scenario: burst`, …) with their API calls (`HTTP POST`) | Tempo: `{ resource.service.name = "bank-ui" }` |
| page load (`documentLoad`, `documentFetch`, `resourceFetch`) | Tempo |
| `ui_actions_total{action, outcome}` | Prometheus |
| web vitals: `browser_web_vitals_{lcp,inp,fcp,ttfb}_milliseconds_*`, `browser_web_vitals_cls_*`, each with `rating` | Prometheus (sent every 10 s and when the tab is hidden) |
| uncaught errors and failed actions, with stack trace, linked to the action's trace | Loki: `{service_name="bank-ui"}` |

- The scenarios are one trace each: *Concurrent burst ×10* shows the ten parallel requests under one root span.
- If the bundle can't be loaded or `bank.ui.browser-telemetry` is `false`, the UI keeps working and only sends a
  plain `traceparent` header.
- Configuration (`bank.ui.*`, all optional): `grafana-url` and `temporal-ui-url` (the links in the UI, served by
  `GET /api/ui-config`), `browser-telemetry`, and `otlp.traces-url`, `otlp.logs-url`, `otlp.metrics-url` to override
  the collector endpoints that Spring Boot already knows.
- The OpenTelemetry library is vendored as one ES module (`static/vendor/otel-web.js`, about 130 KB), so the UI
  needs no CDN and no Node.js at runtime. The exact versions are pinned in `src/main/ui-telemetry/package.json`.
  To update or rebuild it:

  ```bash
  cd bank-service/src/main/ui-telemetry && npm ci && npm run build
  ```

**Metrics (Prometheus):**

| Metric | Source |
| --- | --- |
| `bank_transfers_finished_total{status, kind}` | workflow, via `Workflow.getMetricsScope()` (replay-safe, no double counting) |
| `bank_transfer_approval_wait_*` | workflow: time between "awaiting approval" and the decision |
| `bank_ledger_lock_milliseconds_*`, `bank_account_command_milliseconds_*` | ledger observations |
| `bank_ledger_sum_cents` | ledger gauge: the sum of all postings, always 0 (`LedgerMetrics`) |
| `bank_transfers_in_flight{status}`, `bank_transfers_in_flight_age_max_seconds{status}` | ledger gauges: unfinished transfers (PENDING, DEBITED) and the age of the oldest |
| `temporal_*` (about 50 metrics) | Temporal SDK: workflow/activity latencies, schedule-to-start, failures, pollers, … |
| `http_server_*`, `hikaricp_*`, `jvm_*` | Spring Boot |

Example: `sum by (status, kind) (bank_transfers_finished_total)` or
`histogram_quantile(0.95, sum by (le) (rate(bank_ledger_lock_milliseconds_bucket[5m])))`.

- The `bank.*` timers and `http.server.requests` publish histogram buckets
  (`management.metrics.distribution.percentiles-histogram`), so percentiles can be computed in Prometheus.
- Prometheus runs with `--enable-feature=created-timestamp-zero-ingestion` (`compose.yaml`): a series that arrives
  with its first value already counted (the first approval, every browser page) starts at 0, so `rate()` and
  `increase()` see that first value too.

**Logs (Loki):** console as usual, plus every log event via OTLP. Temporal puts `WorkflowId`, `RunId`,
`ActivityType`, `Attempt` and `TaskQueue` into the MDC, so each log line carries them as attributes together with
`trace_id`. In Grafana, *Related logs* on a trace jumps to its logs. Query example:
`{service_name="bank-service"} | WorkflowId = "transfer-<id>"`.

- In workflows, log with `Workflow.getLogger(...)`: it does not log again while a workflow is replayed.
- Logs and spans only contain pseudonymous IDs (transfer, account), never names, IBANs, amounts or balances. Metrics
  only contain aggregates. Amounts are in the ledger and, until stage 9 encrypts them, in the Temporal UI.

**Dashboard and alerts (Grafana):** provisioned from `observability/grafana` on every start of the LGTM container.

- Dashboard *PowerBank overview* (Dashboards → PowerBank, http://localhost:3000/d/powerbank-overview): ledger sum,
  transfers in flight and the oldest waiting one, firing alerts, finished transfers, approval wait, lock waits and
  account commands, external bank outcomes and refund share, Temporal schedule-to-start and activity failures, REST
  API rates and latencies, browser web vitals and UI actions, and the warnings and errors from Loki. Links at the top
  open Tempo, Loki and the alert rules.
- Alert rules (Alerting → Alert rules → folder PowerBank), evaluated every 30 seconds:

| Alert | Fires when | Severity |
| --- | --- | --- |
| Ledger unbalanced | `bank_ledger_sum_cents` is not 0 | critical |
| External bank does not accept payments | more than 50 % of the calls rejected or failed in 5 minutes (at least 5 calls), for 1 minute | warning |
| Many external transfers refunded | more than 30 % of the finished external transfers refunded in 15 minutes (at least 5) | warning |
| Transfer waiting for approval for over a day | oldest PENDING transfer older than 24 hours | warning |
| External transfer stuck | oldest DEBITED transfer older than 15 minutes (the workflow refunds after 10) | warning |
| Temporal workers can't keep up | schedule-to-start p95 of workflow tasks or activities above 1 second for 2 minutes | warning |
| REST API returns server errors | any 5xx response in 5 minutes | warning |
| JavaScript errors in the UI | any error log from `bank-ui` in 5 minutes | warning |
| Temporal server down | its metrics endpoint doesn't answer for 1 minute | critical |
| Nobody works on the bank task queue | a task waits more than 60 seconds in the `bank` task queue, for 1 minute (measured by the server, so it also fires when the bank-service is down) | critical |
| Temporal server can't reach its database | persistence errors of type Unavailable, Timeout, DeadlineExceeded or Internal | warning |
| Postgres unreachable | the collector can't read the database size for 1 minute | critical |
| Postgres connections almost used up | more than 80 % of `max_connections` for 2 minutes | warning |
| Postgres deadlocks | any deadlock in 10 minutes | warning |

- No data (the bank-service isn't running, no traffic) counts as OK. Notifications aren't configured yet: firing
  alerts are visible in Grafana and on the dashboard.
- Try it: the simulator preset *Chaos* plus a few external transfers fires the external bank alerts.
- Changing them: Grafana re-reads the dashboard JSON every 10 seconds, but doesn't save changes made in the UI to
  the file. Edit in the UI, export the JSON (*Export → Export as JSON*) and replace
  `observability/grafana/dashboards/powerbank-overview.json`. Alert rules are read at startup:
  `docker compose restart lgtm`. `GrafanaProvisioningTest` checks that the files fit together.
- A second dashboard, *PowerBank infrastructure*, shows the Temporal server and Postgres (see below). The
  dashboards link to each other.
- If the ledger alert ever fires, this finds the transfer that doesn't sum up to zero:
  `SELECT transfer_id, SUM(amount_cents) FROM postings GROUP BY transfer_id HAVING SUM(amount_cents) <> 0;`

**Infrastructure (Temporal server, Postgres):** the OpenTelemetry Collector inside the LGTM container gets a second
config file, `observability/otel-collector/infrastructure.yaml` (via `OTELCOL_EXTRA_ARGS` in `compose.yaml`). It adds
its own pipelines and leaves the image's untouched:

| Source | How | In Grafana |
| --- | --- | --- |
| Temporal server metrics | scrapes `temporal:9233/metrics` every 15 s, keeps only requests, errors, latencies, persistence, task queue backlog, completed workflows and goroutines (about 2,000 of 12,000 series) | Prometheus, `job="temporal-server"`, e.g. `approximate_backlog_age_seconds{taskqueue="bank"}` |
| Postgres metrics | `postgresql` receiver on `pg_stat_*`: connections, commits and rollbacks, row changes, locks, deadlocks, cache hits, table sizes, sequential scans | Prometheus, `job="postgres"`, `postgresql_*` |
| Postgres logs | JSON log files on the volume `postgres-logs`; slow statements (from 250 ms) and lock waits (from 1 s) included | Loki, `{service_name="postgres"}` |
| Temporal server logs | its JSON output (warnings and errors) is copied into a file on the `temporal-data` volume | Loki, `{service_name="temporal-server"}` |

- No personal data in these logs either: Postgres logs no statement parameters, and since its JSON format always
  writes DETAIL (e.g. a failing row with its values), the collector passes on only a few safe fields (severity,
  error code, backend type, database, session). The app's statements only contain `$1` placeholders; only
  statements typed by hand in `psql` show their literals in the slow statement log.
- Some Temporal server "errors" are normal: `WorkflowExecutionAlreadyStarted` (duplicate submission),
  `NotFound`, `Canceled`, `ConditionFailed`. The persistence alert only looks at real failures.
- The postgresql receiver uses the `bank` user locally; in production it gets a role with only `pg_monitor`.

**Temporal connection:** `spring.temporal.connection.target` is `localhost:7233`, not the `local` shortcut. With
`local`, the SDK reported no `temporal_*` metrics in this setup.

## Architecture rules

`ArchitectureTest` (ArchUnit) turns the module boundaries and Temporal's determinism rules into tests:

- Workflow code doesn't depend on the ledger, the API, Spring, JDBC, coroutines or fmodel. It only knows Temporal
  and its activity contracts.
- Workflow code doesn't call `Instant.now()`, `System.currentTimeMillis()`, `UUID.randomUUID()`, `Thread.sleep()`,
  `Random`, … (use `Workflow.currentTimeMillis()`, `Workflow.randomUUID()`, `Workflow.sleep()`).
- The domain only depends on Kotlin, coroutines and fmodel; the ledger doesn't know its callers; controllers don't
  touch SQL.
- A deliberately broken fixture (`NonDeterministicWorkflow`) proves the rules actually fire.

## Trying it out with curl

```bash
# Open an account (no application process until stage 7) and deposit 1,000 €
curl -s -X POST localhost:8080/api/accounts -H 'Content-Type: application/json' -d '{}'
curl -s -X POST localhost:8080/api/accounts/<account>/deposits -H 'Content-Type: application/json' -d '{"amountCents":100000}'

# Internal transfer; the transferId protects against duplicate submission (second POST → 409)
curl -s -X POST localhost:8080/api/transfers -H 'Content-Type: application/json' \
  -d '{"transferId":"<uuid>","debtorAccountId":"<account>","creditorAccountId":"<account2>","amountCents":2500}'

# Transfer to an external bank (saga). The simulator always rejects IBANs ending in 000000.
curl -s -X POST localhost:8080/api/transfers -H 'Content-Type: application/json' \
  -d '{"debtorAccountId":"<account>","creditorIban":"DE89 3704 0044 0532 0130 00","amountCents":4000}'

# Status (query) and approval on suspected fraud (update): from 10,000 € internally, 5,000 € to external banks
curl -s localhost:8080/api/transfers/<transferId>
curl -s -X POST localhost:8080/api/transfers/<transferId>/approval -H 'Content-Type: application/json' -d '{"approved":true}'

# Balance and postings
curl -s localhost:8080/api/accounts/<account>
curl -s localhost:8080/api/accounts/<account>/postings
```

More endpoints:

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/api/accounts` | all accounts with balances (customer and system) |
| `POST` | `/api/accounts/{id}/block`, `/unblock` | block or unblock an account |
| `GET` | `/api/transfers?accountId=&limit=` | transfers, newest first, with live status for those in flight |
| `GET` | `/api/transfers/{id}/postings` | the postings a transfer created |
| `GET` | `/api/ledger/summary` | sum of all postings (must be 0), counts |
| `GET`, `PUT` | `/api/simulator/external-bank` | external bank simulator settings |

## Structure

```
domain/          fmodel decider (account) and risk rules: pure business logic, only fmodel + coroutines
observability/grafana/  dashboards and alert rules, mounted into the LGTM container by compose.yaml
observability/otel-collector/  collector config for Temporal server and Postgres metrics and logs
bank-service/
  ledger/        AccountEventRepository (postings = events), Ledger (transactions, locks), queries
  workflow/      TransferWorkflow
  activities/    LedgerActivities, RiskActivities, ExternalBankActivities
  externalbank/  external bank simulator: slow, fails technically, sometimes rejects
  api/           REST
  config/        Temporal data converter, OpenTelemetry log appender, which requests get traced
  observability/ span attributes, UI config, OTLP proxy for the browser
  resources/static/  the UI (HTML, CSS, JS); telemetry.js and vendor/otel-web.js for browser telemetry
  ui-telemetry/  build of the vendored OpenTelemetry bundle (npm + esbuild, not part of the Gradle build)
```

**How a transfer works** (`TransferWorkflow`, workflow ID `transfer-<transferId>`):

1. `RiskActivities.assess`: if the amount exceeds the limit, the workflow waits up to 3 days for the `approve` update.
   Meanwhile, the `status()` query shows `AWAITING_APPROVAL`.
2. **Internal:** one activity, one database transaction: debit and credit. No saga needed.
3. **External bank (saga):** debit (the money goes to the clearing account) → external bank → refund on rejection.

**Ledger:**

- Every posting is an immutable row in `postings` (a trigger forbids UPDATE and DELETE). The balance is their sum.
- A deferred constraint trigger enforces at commit time that every transfer sums up to zero.
- The `AccountEventRepository` locks the account row with `SELECT … FOR UPDATE` and hands the postings to the decider
  as events. Accounts are always locked sorted by ID, so there are no deadlocks.
- Idempotency: the first decision for a transfer ID stands, even across activity retries. On top of that there's
  the unique constraint `(transfer_id, account_id, leg)`.
- System accounts: cash (`…0001`) for deposits, clearing account for external banks (`…0002`). They may go negative.

## Roadmap

- [x] **1. Warm-up:** killed the worker with `kill -9` in the middle of running external transfers. After the restart
      all of them finished and the balances were correct. (The official Java tutorial is left as your exercise.)
- [x] **2. Ledger without Temporal:** `LedgerTest` shows that booking twice changes nothing and all postings sum up to zero.
- [x] **3. Account decider:** sufficient funds, overdraft, blocked and closed accounts; Given/When/Then in `AccountDeciderTest`.
- [x] **4. Aggregate and internal transfer:** duplicate submission → 409; concurrent transfers never overdraw the
      account (tested in the ledger and through REST and workflows).
- [x] **5. External bank saga:** rejection and permanent unavailability lead to a refund, all balances add up.
- [ ] **6. Standing orders** (Temporal schedules), planned in [issue #1](https://github.com/MikaSchulz/PowerBank/issues/1)
- [ ] **7. Account opening with approval** (replaces `POST /api/accounts` and the placeholder customer)
- [x] **8. Suspected fraud:** the query shows `AWAITING_APPROVAL`; approval, denial and deadline expiry are tested,
      the deadline tests with time skipping.
- [ ] **9. Encryption** (payload codec, codec server; there's a spot for it in `TemporalConfig`)
- [ ] **10. Account closure and crypto-shredding** (`CloseAccount` already exists in the decider)
- [ ] **11. Failures and versioning**
- [ ] **12. Bonus: Quarkus worker** (`ExternalBankActivities` is already a separate interface for this)

## Deviations from the original plan

- **Versions:** Temporal 1.40.0 instead of 1.33.0 (only from there on does the starter know Spring Boot 4's package
  structure), fmodel 3.7.0 instead of 3.6.0.
- **Jackson for Temporal:** `KotlinObjectMapperFactory` from `temporal-kotlin` is compiled against an old
  jackson-module-kotlin and fails with Spring Boot 4, so `TemporalConfig` builds the ObjectMapper itself.
- **Schema additions:** `accounts.kind` (customer or system) and `accounts.overdraft_limit_cents`,
  `transfers.reason` and `updated_at`, plus the two triggers. Migration V2 adds the transfer details
  (`kind`, debtor, creditor, amount) that the API registers when it accepts a transfer, for listings.
- **Status events:** opening, blocking and closing are decider events, but they are stored only as the current status
  in `accounts.status`. When loading, they are turned back into events.
- **Activity signatures:** small dedicated DTOs (including `BookingOutcome`) instead of `AccountCommand` and
  `AccountEvent`, so Temporal doesn't have to serialize polymorphic types and the workflow depends neither on `domain`
  nor on the ledger.
- **Temporal target:** `localhost:7233` instead of `local` (see *Observability*).
- **Observability:** Grafana LGTM in `compose.yaml` and OpenTelemetry for traces, metrics and logs (not in the
  original plan).

## Known learning points

- **External bank unreachable:** after 10 minutes the workflow refunds the debit. In real life it's then unclear
  whether the external bank booked the payment after all; you'd need a status inquiry. The simulator is idempotent per
  transfer ID, but forgets its decisions on restart.
- **Hot rows:** every deposit locks the cash account, every external transfer locks the clearing account. System
  accounts wouldn't need a lock (they have no funds check) and no balance fold either. A candidate for the balance snapshot.
- **Two stores, one truth:** the ledger lives in Postgres, the running transfers in Temporal. Locally both are on
  volumes and reset together (`docker compose down -v`); until #40 brought the volume, the dev server kept
  workflows in memory and lost them on every restart. If the two ever get out of sync, transfers stay PENDING or
  DEBITED without a workflow: the dashboard shows them under *Oldest waiting transfer* and the alerts fire. A
  reconciliation job that finds them is planned in #40; in production Temporal needs its own database (#36).
- **IBAN in the workflow:** for external transfers, the creditor IBAN is part of the workflow input. Stage 9 encrypts it
  with the payload codec. Even more data-minimizing: a payee ID that only the activity resolves.
