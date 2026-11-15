# Observability

Traces, metrics and logs go via OpenTelemetry (OTLP) to the `grafana/otel-lgtm` container: Grafana with Tempo,
Prometheus and Loki. The banks and the clearing house send OTLP/HTTP to `localhost:4318` (`application.yml`).
Open Grafana at http://localhost:3000 → *Explore*.

Every service is its own `service.name`: `bank-a`, `bank-b`, `clearing`, and `bank-ui` for the browser. In Prometheus
and Loki that is the label `service_name`, in Tempo `resource.service.name`; it is how dashboards and alerts tell the
banks apart.

## Traces (Tempo)

one trace per user action, from the click in the browser through Temporal into the database:

```
send transfer                                        ← bank-ui: the click
└─ HTTP POST                                         ← bank-ui: the fetch call
   └─ http post /api/customer/accounts/{id}/transfers   ← bank-service from here on
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

## W3C trace context (`traceparent`)

one trace ID from the click in the browser down to the SQL.

- Every user action in the UI is the root span of a new trace; each API call in it is a client span whose
  `traceparent` (`00-<trace-id>-<span-id>-01`, sampled) the backend continues. Its polling sends the flag `00`
  (not sampled), so Tempo only contains what you actually did.
- Spring Boot continues an incoming `traceparent`; without one it starts a new trace.
- Temporal carries the context in the workflow header `_tracer-data` as a W3C `traceparent`, so the workflow and all
  its activities continue the same trace, also after a worker restart.
- The trace ID is stored with the transfer (`transfers.trace_id`), returned when the transfer is accepted and listed
  with the transfers. The transfer drawer in the UI links straight to the trace in Grafana.
- Only `/api/` requests are traced; the UI's static files, actuator calls and the browser telemetry proxy
  (`/api/otel/`) are skipped (`ObservabilityConfig`).
- `TracePropagationTest` proves all of this with an in-memory span exporter.

## Searching traces

In Grafana → Explore → Tempo, with TraceQL: spans carry pseudonymous business IDs, never names or IBANs.

| Find | TraceQL |
| --- | --- |
| one transfer | `{ span.bank.transfer.id = "<transfer-id>" }` or `{ span.workflowId = "transfer-<transfer-id>" }` |
| everything that touched an account | `{ span.bank.account.id = "<account-id>" }` (ledger commands) |
| transfers from or to an account | `{ span.bank.debtor.account.id = "<id>" \|\| span.bank.creditor.account.id = "<id>" }` |
| failed calls to the clearing house | `{ name = "bank.clearing.call" && span.outcome = "failure" }` |
| one payment between banks, on both sides and at the clearing house | `{ span.bank.payment.uetr = "<uetr>" }` (an outgoing payment's UETR is its transfer ID) |
| payments that passed all three services | `{ resource.service.name = "bank-a" } && { resource.service.name = "clearing" } && { resource.service.name = "bank-b" }` |
| slow lock waits | `{ name = "bank.ledger.lock" && duration > 50ms }` |

The account drawer in the UI links to the account's traces. Every call to the clearing house is its own span
(`bank.clearing.call`) with `message` (pacs.008, pacs.002 for the answer to an incoming payment, pacs.028), `outcome`
(received or refused for a pacs.008 or pacs.002; ACSC, RJCT, PDNG or unknown for a pacs.028; failure for a technical
error), the payment's UETR (`bank.payment.uetr`) and `temporal.activity.attempt`; its timer
`bank_clearing_call_milliseconds_*{message,outcome}` counts them in Prometheus. The timer
`bank_transfer_clearing_wait{direction,resolved_by}` measures how long a payment, outgoing or incoming, waited for its
final status, and whether the confirmation or a status inquiry brought it.

A payment between the banks is one trace across three services, over HTTP: bank A's `pacs.008` carries the
`traceparent` to the clearing house, whose workflow carries it on (Temporal header) to its forward to bank B, whose
answer and the clearing house's confirmations continue it. The showcase's smoke test checks this
([`showcase/smoke_test.py`](../showcase/smoke_test.py)).
- Try it with curl: `-H 'traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01'`, then search for
  that trace ID in Grafana.

## Browser (`service_name="bank-ui"`)

the UI runs the OpenTelemetry JS SDK (`static/telemetry.js`) and sends
traces, logs and metrics as OTLP/HTTP to the bank-service, which forwards them unchanged to the collector
(`POST /api/otel/v1/{traces,logs,metrics}`, `BrowserTelemetryController`). The browser can't reach the collector
itself (other port, CORS), and in the cloud the proxy is where a login will be required ([#36](https://github.com/MikaSchulz/PowerBank/issues/36)).

| What | Where |
| --- | --- |
| user actions (`send transfer`, `approve transfer`, `scenario: burst`, …) with their API calls (`HTTP POST`) | Tempo: `{ resource.service.name = "bank-ui" }`, one area: `{ resource.bank.ui.area = "backoffice" }` |
| page load (`documentLoad`, `documentFetch`, `resourceFetch`) | Tempo |
| `ui_actions_total{action, area, outcome}` | Prometheus |
| web vitals: `browser_web_vitals_{lcp,inp,fcp,ttfb}_milliseconds_*`, `browser_web_vitals_cls_*`, each with `rating` | Prometheus (sent every 10 s and when the tab is hidden) |
| uncaught errors and failed actions, with stack trace, linked to the action's trace | Loki: `{service_name="bank-ui"}` |

- Every page of the UI is one area (#61): `customer`, `backoffice`, `operations` (`home` for the start page), as the
  resource attribute `bank.ui.area` and on every action span and the `ui_actions` metric.
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

## Metrics (Prometheus)

| Metric | Source |
| --- | --- |
| `bank_transfers_finished_total{status, kind}` | workflow, via `Workflow.getMetricsScope()` (replay-safe, no double counting) |
| `bank_transfer_approval_wait_*` | workflow: time between "awaiting approval" and the decision |
| `bank_ledger_lock_milliseconds_*`, `bank_account_command_milliseconds_*`, `bank_transfer_command_milliseconds_*` | ledger observations |
| `bank_ledger_sum_cents` | ledger gauge: the sum of all postings, always 0 (`LedgerMetrics`) |
| `bank_transfers_in_flight{status}`, `bank_transfers_in_flight_age_max_seconds{status}` | ledger gauges: unfinished transfers per business status and how long the longest-waiting one has been in it |
| `bank_clearing_call_milliseconds_*{message, outcome}`, `bank_transfer_clearing_wait_*{direction, resolved_by}` | the bank's messages to the clearing house, and how long payments waited for their final status |
| `clearing_payments_finished_total{status, reason, debtor_bank, creditor_bank}` | the clearing house's workflow: payments by final status and reason code, per route (bank codes) |
| `clearing_payment_duration_milliseconds_*{status, debtor_bank, creditor_bank}` | the clearing house's workflow: from the `pacs.008` to the final status (the settlement time) |
| `temporal_*` (about 50 metrics) | Temporal SDK: workflow/activity latencies, schedule-to-start, failures, pollers, … |
| `http_server_*`, `hikaricp_*`, `jvm_*` | Spring Boot |

Every series carries `service_name` (`bank-a`, `bank-b`, `clearing`). Examples:
`sum by (service_name, status, kind) (bank_transfers_finished_total)`,
`histogram_quantile(0.95, sum by (le) (rate(bank_ledger_lock_milliseconds_bucket{service_name="bank-b"}[5m])))`,
`sum by (creditor_bank) (rate(clearing_payments_finished_total{reason="AB05"}[5m]))`.

- The `bank.*` and `clearing.*` timers and `http.server.requests` publish histogram buckets
  (`management.metrics.distribution.percentiles-histogram`), so percentiles can be computed in Prometheus.
- Bank codes (`99000001` bank A, `99000002` bank B) identify banks, not people, so they may be labels; IBANs and
  amounts never are.
- Prometheus runs with `--enable-feature=created-timestamp-zero-ingestion` (`compose.yaml`): a series that arrives
  with its first value already counted (the first approval, every browser page) starts at 0, so `rate()` and
  `increase()` see that first value too.

## Logs (Loki)

console as usual, plus every log event via OTLP. Temporal puts `WorkflowId`, `RunId`,
`ActivityType`, `Attempt` and `TaskQueue` into the MDC, so each log line carries them as attributes together with
`trace_id`. In Grafana, *Related logs* on a trace jumps to its logs. Query example:
`{service_name="bank-a"} | WorkflowId = "transfer-<id>"`. Every bank is its own service: `bank-a`, and `bank-b` for the second bank (#57).

- In workflows, log with `Workflow.getLogger(...)`: it does not log again while a workflow is replayed.
- Logs and spans only contain pseudonymous IDs (transfer, account), never names, IBANs, amounts or balances. Metrics
  only contain aggregates. Amounts are in the ledger and, until stage 9 encrypts them, in the Temporal UI.

## Dashboards and alerts (Grafana)

The dashboards and alert rules are provisioned from `observability/grafana` on every start of the LGTM container.

- Dashboard *PowerBank overview* (Dashboards → PowerBank, http://localhost:3000/d/powerbank-overview), for bank A,
  bank B or both (the variable *Bank* at the top, `service_name`):
  - ledger sum per bank, transfers in flight and the oldest waiting one, firing alerts;
  - finished transfers, approval wait, lock waits and account commands;
  - the calls to the clearing house, failed calls and the share of rejected interbank payments;
  - Temporal schedule-to-start and activity failures, REST API rates and latencies;
  - browser web vitals and UI actions (`bank-ui`, both banks together);
  - the warnings and errors from Loki.

  Links at the top open Tempo, Loki and the alert rules.
- Dashboard *PowerBank clearing house* (http://localhost:3000/d/powerbank-clearing):
  - payments per minute, settled share and settlement time p95, firing alerts;
  - payments by route (debtor bank → creditor bank) and by outcome with the reason code;
  - the settlement time, overall and per creditor bank;
  - timeouts (`AB05`) per creditor bank and their share;
  - the banks' messages by HTTP status, failed deliveries to the banks, and the clearing house's warnings and errors.
- Alert rules (Alerting → Alert rules → folder PowerBank), evaluated every 30 seconds. The banks' rules fire per bank:
  one alert instance per `service_name`, and the summary names the bank.

| Alert | Fires when | Severity |
| --- | --- | --- |
| Ledger unbalanced | a bank's `bank_ledger_sum_cents` is not 0 | critical |
| Clearing house not reachable | more than 50 % of a bank's calls to the clearing house failed in 5 minutes (at least 5 calls), for 1 minute | warning |
| Many interbank payments rejected | more than 30 % of a bank's payments through the clearing house failed in 10 minutes (at least 10): outgoing ones refunded, incoming ones rejected | warning |
| Creditor bank misses the deadline | the clearing house rejected more than 30 % of the payments to a bank with `AB05` in 10 minutes (at least 5), per creditor bank | warning |
| Transfer waiting for approval for over a day | a transfer has been `AWAITING_APPROVAL` for more than 24 hours | warning |
| External transfer stuck | a transfer has been `RESERVED` or `SENT_TO_EXTERNAL_BANK` for more than 15 minutes (the workflow investigates after 5) | warning |
| Interbank payments under investigation | any payment of a bank `UNDER_INVESTIGATION`, outgoing or incoming: no final status from the clearing house after 5 minutes | warning |
| Transfer stuck before booking | a transfer has been `RECEIVED` or `APPROVED` for more than 15 minutes | warning |
| Temporal workers can't keep up | schedule-to-start p95 of workflow tasks or activities above 1 second for 2 minutes | warning |
| REST API returns server errors | any 5xx response in 5 minutes | warning |
| JavaScript errors in the UI | any error log from `bank-ui` in 5 minutes | warning |
| Temporal server down | its metrics endpoint doesn't answer for 1 minute | critical |
| Nobody works on a task queue | a task waits more than 60 seconds in the `bank` task queue of a bank's namespace or in the clearing house's `clearing`, for 1 minute (measured by the server, so it also fires when the service is down) | critical |
| Temporal server can't reach its database | persistence errors of type Unavailable, Timeout, DeadlineExceeded or Internal | warning |
| Postgres unreachable | the collector can't read the database size for 1 minute | critical |
| Postgres connections almost used up | more than 80 % of `max_connections` for 2 minutes | warning |
| Postgres deadlocks | any deadlock in 10 minutes | warning |

- No data (the services aren't running, no traffic) counts as OK. Notifications aren't configured yet: firing
  alerts are visible in Grafana and on the dashboards.
- Try it: the showcase's chaos phases fire them for you (see the [showcase](showcase.md#what-the-demo-traffic-does)).
  Or set the clearing house to *Clearing house down* in operations and send a few payments to the other bank:
  "Clearing house not reachable" fires, and after 5 minutes "Interbank payments under investigation".
- A rule that is removed from the file stays in Grafana; list its UID under `deleteRules` in the same file.
- Changing them: Grafana re-reads the dashboard JSON every 10 seconds, but doesn't save changes made in the UI to
  the file. Edit in the UI, export the JSON (*Export → Export as JSON*) and replace the file in
  `observability/grafana/dashboards`. Alert rules are read at startup:
  `docker compose restart lgtm`. `GrafanaProvisioningTest` checks that the files fit together.
- A third dashboard, *PowerBank infrastructure*, shows the Temporal server and Postgres (see [Infrastructure](#infrastructure-temporal-server-postgres)). The
  dashboards link to each other.
- If the ledger alert ever fires, this finds the transfer that doesn't sum up to zero:
  `SELECT transfer_id, SUM(amount_cents) FROM postings GROUP BY transfer_id HAVING SUM(amount_cents) <> 0;`

## Infrastructure (Temporal server, Postgres)

the OpenTelemetry Collector inside the LGTM container gets a second
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
