# PowerBank: a banking system on Temporal

[![CI](https://github.com/MikaSchulz/PowerBank/actions/workflows/ci.yml/badge.svg)](https://github.com/MikaSchulz/PowerBank/actions/workflows/ci.yml)

Accounts, transfers and (soon) standing orders, running entirely locally. **The ledger is the source of truth**,
Temporal orchestrates everything that takes time or crosses system boundaries, and **fmodel** makes the business decisions.

**Stack:** Kotlin 2.3, Spring Boot 4.1, Temporal Java SDK 1.40 with the Spring Boot starter, fmodel 3.7, Postgres 18, Gradle 9.

## Getting started

Prerequisites: Java 25 and Docker. Gradle comes with the wrapper.

```bash
./gradlew :bank-service:bootRun
```

On startup, Spring Boot runs `compose.yaml` (Postgres, the Temporal dev server and Grafana LGTM) and keeps the
containers running afterwards (`start-only`), so workflow histories survive app restarts.

- UI: http://localhost:8080
- API: http://localhost:8080/api
- Temporal Web UI: http://localhost:8233
- Grafana (traces, metrics, logs): http://localhost:3000

Tests (Postgres via Testcontainers, Temporal in memory):

```bash
./gradlew test
```

Without Java 25, Java 21 works too: `./gradlew -PjavaVersion=21 test`. CI (GitHub Actions) builds and tests every pull request and every push to `main` on Java 25.

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
Prometheus and Loki. Spring Boot detects the container through Docker Compose and configures the endpoints itself.
Open Grafana at http://localhost:3000 → *Explore*.

**Traces (Tempo):** one trace per request, from the HTTP call through Temporal into the database:

```
http post /api/transfers
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

- The UI sends a fresh `traceparent` with every user action (`00-<trace-id>-<span-id>-01`, sampled). Its polling
  sends the flag `00` (not sampled), so Tempo only contains what you actually did.
- Spring Boot continues an incoming `traceparent`; without one it starts a new trace.
- Temporal carries the context in the workflow header `_tracer-data` as a W3C `traceparent`, so the workflow and all
  its activities continue the same trace, also after a worker restart.
- The trace ID is stored with the transfer (`transfers.trace_id`), returned by `POST /api/transfers` and listed by
  `GET /api/transfers`. The transfer drawer in the UI links straight to the trace in Grafana.
- Only `/api/` requests are traced; the UI's static files and actuator calls are skipped (`ObservabilityConfig`).
- `TracePropagationTest` proves all of this with an in-memory span exporter.
- Try it with curl: `-H 'traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01'`, then search for
  that trace ID in Grafana.

**Metrics (Prometheus):**

| Metric | Source |
| --- | --- |
| `bank_transfers_finished_total{status, kind}` | workflow, via `Workflow.getMetricsScope()` (replay-safe, no double counting) |
| `bank_transfer_approval_wait_*` | workflow: time between "awaiting approval" and the decision |
| `bank_ledger_lock_milliseconds_*`, `bank_account_command_milliseconds_*` | ledger observations |
| `temporal_*` (about 50 metrics) | Temporal SDK: workflow/activity latencies, schedule-to-start, failures, pollers, … |
| `http_server_*`, `hikaricp_*`, `jvm_*` | Spring Boot |

Example: `sum by (status, kind) (bank_transfers_finished_total)` or
`histogram_quantile(0.95, sum by (le) (rate(bank_ledger_lock_milliseconds_bucket[5m])))`.

**Logs (Loki):** console as usual, plus every log event via OTLP. Temporal puts `WorkflowId`, `RunId`,
`ActivityType`, `Attempt` and `TaskQueue` into the MDC, so each log line carries them as attributes together with
`trace_id`. In Grafana, *Related logs* on a trace jumps to its logs. Query example:
`{service_name="bank-service"} | WorkflowId = "transfer-<id>"`.

- In workflows, log with `Workflow.getLogger(...)`: it does not log again while a workflow is replayed.
- Logs, spans and metric tags only contain pseudonymous IDs and amounts, never names or IBANs.

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
bank-service/
  ledger/        AccountEventRepository (postings = events), Ledger (transactions, locks), queries
  workflow/      TransferWorkflow
  activities/    LedgerActivities, RiskActivities, ExternalBankActivities
  externalbank/  external bank simulator: slow, fails technically, sometimes rejects
  api/           REST
  config/        Temporal data converter, OpenTelemetry log appender
  resources/static/  the UI (HTML, CSS, JS)
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
- **IBAN in the workflow:** for external transfers, the creditor IBAN is part of the workflow input. Stage 9 encrypts it
  with the payload codec. Even more data-minimizing: a payee ID that only the activity resolves.
