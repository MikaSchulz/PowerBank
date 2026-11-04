# Architecture

Decisions behind it: [decisions](decisions/README.md).

## Modules

```
domain/          fmodel deciders (account, transfer) and risk rules: pure business logic, only fmodel + coroutines
observability/grafana/  dashboards and alert rules, mounted into the LGTM container by compose.yaml
observability/otel-collector/  collector config for Temporal server and Postgres metrics and logs
bank-service/
  ledger/        event repositories (postings, transfer history), Ledger (transactions, locks), queries
  workflow/      TransferWorkflow
  activities/    LedgerActivities, RiskActivities, ExternalBankActivities
  externalbank/  external bank simulator: slow, fails technically, sometimes rejects
  api/           REST
  config/        Temporal data converter, OpenTelemetry log appender, which requests get traced
  observability/ span attributes, UI config, OTLP proxy for the browser
  resources/static/  the UI (HTML, CSS, JS); telemetry.js and vendor/otel-web.js for browser telemetry
  ui-telemetry/  build of the vendored OpenTelemetry bundle (npm + esbuild, not part of the Gradle build)
```

## How a transfer works

The API receives the transfer in the ledger (status `RECEIVED`) and starts the workflow (`TransferWorkflow`, workflow
ID `transfer-<transferId>`):

1. `RiskActivities.assess`: if the amount exceeds the limit, the ledger records `AWAITING_APPROVAL` and the workflow
   waits up to 3 days for the `approve` update. The ledger then records the decision: `APPROVED`, or `REJECTED` when
   denied or not decided in time.
2. **Internal:** one activity, one database transaction: the status `BOOKED`, debit and credit. No saga needed.
3. **External bank (saga):** debit, the money goes to the clearing account (`SENT_TO_EXTERNAL_BANK`) → external bank
   → `COMPLETED`, or `REFUNDED` on rejection.

Who decides what ([decision 0007](decisions/0007-what-waits-in-a-workflow-and-what-is-domain-state.md)):

| | Decides | Lives in |
| --- | --- | --- |
| Transfer decider | which status change is allowed, rejection reasons | `domain`, run by the ledger |
| Account decider | whether money may move: funds, overdraft, blocked or closed accounts | `domain`, run by the ledger |
| Workflow | when a step is attempted, waiting, deadlines, retries, compensation | Temporal |

```
RECEIVED ─┬───────────────────────────────┬─→ BOOKED                      (internal, deposit)
          └→ AWAITING_APPROVAL → APPROVED ┴─→ SENT_TO_EXTERNAL_BANK ─┬→ COMPLETED
                                                                      └→ REFUNDED
RECEIVED, AWAITING_APPROVAL, APPROVED ─→ REJECTED (nothing booked)
```

## Ledger

- Every posting is an immutable row in `postings` (a trigger forbids UPDATE and DELETE). The balance is their sum.
- A deferred constraint trigger enforces at commit time that every transfer sums up to zero.
- The `AccountEventRepository` locks the account row with `SELECT … FOR UPDATE` and hands the postings to the decider
  as events. Accounts are always locked sorted by ID, so there are no deadlocks.
- A booking step runs both deciders in one transaction: first the transfer decider (is the step allowed, or did it
  already happen?), then the account decider for debit and credit. If an account rejects, everything is rolled back
  and the transfer is rejected instead. Locks: always the transfer row first, then the accounts.
- Idempotency: the first decision for a transfer ID stands, even across activity retries. On top of that there's
  the unique constraint `(transfer_id, account_id, leg)`.
- `transfers.status` is the current business status; `transfer_events` keeps every change with its reason (append-only,
  like the postings). `GET /api/transfers/{id}/history` and the transfer drawer in the UI show it.
- System accounts: cash (`…0001`) for deposits, clearing account for external banks (`…0002`). They may go negative.

## Architecture rules

`ArchitectureTest` (ArchUnit) turns the module boundaries and Temporal's determinism rules into tests:

- Workflow code doesn't depend on the ledger, the API, Spring, JDBC, coroutines or fmodel. It only knows Temporal
  and its activity contracts.
- Workflow code doesn't call `Instant.now()`, `System.currentTimeMillis()`, `UUID.randomUUID()`, `Thread.sleep()`,
  `Random`, … (use `Workflow.currentTimeMillis()`, `Workflow.randomUUID()`, `Workflow.sleep()`).
- The domain only depends on Kotlin, coroutines and fmodel; the ledger doesn't know its callers; controllers don't
  touch SQL.
- A deliberately broken fixture (`NonDeterministicWorkflow`) proves the rules actually fire.

## Temporal connection

`spring.temporal.connection.target` is `localhost:7233`, not the `local` shortcut. With
`local`, the SDK reported no `temporal_*` metrics in this setup.
