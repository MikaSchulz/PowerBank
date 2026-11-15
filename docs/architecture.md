# Architecture

Decisions behind it: [decisions](decisions/README.md).

## Modules

```
domain/          fmodel deciders (account, transfer) and risk rules: pure business logic, only fmodel + coroutines
payment-messages/  the ISO 20022 messages banks and the clearing house exchange (pacs.008, pacs.002, pacs.028)
clearing/        the clearing house (#58): its own service between the banks, see below
observability/grafana/  dashboards and alert rules, mounted into the LGTM container by compose.yaml
observability/otel-collector/  collector config for Temporal server and Postgres metrics and logs
bank-service/
  ledger/        event repositories (postings, transfer history), Ledger (transactions, locks), queries
  workflow/      TransferWorkflow
  activities/    LedgerActivities, RiskActivities, ClearingHouseActivities (pacs.008, pacs.028 over HTTP)
  externalbank/  the simulated external bank from before SEPA Instant: only for executions started before #59
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
3. **External (SEPA Instant through the clearing house):** reserve the amount on the clearing account (`RESERVED`),
   send the `pacs.008` (`SENT_TO_EXTERNAL_BANK`), wait for the final status → `COMPLETED` (settled) or `REFUNDED`.
   Without a final status: `UNDER_INVESTIGATION`, the amount stays reserved. Details below.

Who decides what ([decision 0007](decisions/0007-workflow-runs-the-process-domain-decides-and-records.md)):

| | Decides | Lives in |
| --- | --- | --- |
| Transfer decider | which status change is allowed, rejection reasons | `domain`, run by the ledger |
| Account decider | whether money may move: funds, overdraft, blocked or closed accounts | `domain`, run by the ledger |
| Workflow | when a step is attempted, waiting, deadlines, retries, compensation | Temporal |

```
RECEIVED ─┬───────────────────────────────┬─→ BOOKED                                          (internal, deposit)
          └→ AWAITING_APPROVAL → APPROVED ┴─→ RESERVED → SENT_TO_EXTERNAL_BANK ─┬→ COMPLETED   (external)
                                                  │                │             └→ REFUNDED
                                                  └────────────────┴─→ UNDER_INVESTIGATION ─→ COMPLETED or REFUNDED
RECEIVED, AWAITING_APPROVAL, APPROVED ─→ REJECTED (nothing booked)
```

### A transfer with an approval, step by step

- **Blue, the workflow:** the process itself. It decides what comes next, waits and reacts to the approval or the
  deadline.
- **Orange, the activities:** every side effect, retried by Temporal until it succeeds.
- **Green, domain and ledger:** the rules (deciders) and the business state (Postgres). They are the only place that
  decides whether a step is allowed and what the status becomes.

```mermaid
sequenceDiagram
    autonumber
    actor C as Customer (UI)
    participant API as REST API
    box rgb(232, 240, 254) Workflow: the process, in Temporal
        participant WF as TransferWorkflow
        participant T as Temporal server<br/>(history, timers)
    end
    box rgb(254, 243, 224) Activities: side effects with retries
        participant RA as RiskActivities
        participant LA as LedgerActivities
    end
    box rgb(230, 244, 234) Domain and ledger: rules and business state
        participant D as Transfer and<br/>account deciders
        participant DB as Postgres<br/>(transfers, transfer_events, postings)
    end
    actor E as Employee (UI)

    C->>API: POST /api/transfers (above the approval limit)
    API->>D: ReceiveTransfer
    D->>DB: status RECEIVED
    API->>T: start workflow transfer-{id}
    API-->>C: 202 Accepted

    T->>WF: run
    WF->>RA: assess
    RA-->>WF: review required
    WF->>LA: requireApproval
    LA->>D: RequireApproval
    D->>DB: status AWAITING_APPROVAL (+ history)
    LA-->>WF: AWAITING_APPROVAL

    rect rgb(245, 245, 245)
        Note over WF,T: Workflow.await(3 days): nothing runs, no thread is blocked.<br/>Temporal keeps the open workflow and a durable timer.
        Note over DB: The business state is visible all the time:<br/>UI, SQL, dashboards and alerts see AWAITING_APPROVAL
    end

    alt approved within 3 days
        E->>API: POST /api/transfers/{id}/approval
        API->>T: update "approve"
        T->>WF: validator, then approve(decision)
        WF->>LA: decideApproval(approved)
        LA->>D: Approve
        D->>DB: status APPROVED
        LA-->>WF: APPROVED
        WF-->>API: update result APPROVED
        API-->>E: 200 OK
        WF->>LA: bookInternalTransfer
        Note over LA,DB: one transaction: BookTransfer, then debit and credit
        LA->>D: BookTransfer, Debit, Credit
        D->>DB: status BOOKED + 2 postings
        opt Postgres briefly unreachable
            LA--xT: attempt fails
            T->>LA: retry with backoff (idempotent: same transfer ID)
            Note over WF: the workflow code doesn't notice retries
        end
        LA-->>WF: BOOKED
    else no decision within 3 days
        T->>WF: timer fires
        WF->>LA: expireApproval
        LA->>D: ExpireApproval
        D->>DB: status REJECTED "No approval within the deadline"
        LA-->>WF: REJECTED
    end
    WF->>T: workflow completed
```

### An external transfer: SEPA Instant

The bank sends the payment to the clearing house and acts only on a **final status**. The UETR of the payment is the
transfer ID, so every message about it finds the transfer (and its workflow) again.

```mermaid
sequenceDiagram
    autonumber
    box rgb(232, 240, 254) Workflow: the process, in Temporal
        participant WF as TransferWorkflow
    end
    box rgb(254, 243, 224) Activities: side effects with retries
        participant LA as LedgerActivities
        participant CA as ClearingHouseActivities
    end
    box rgb(230, 244, 234) Ledger: rules and business state
        participant DB as Transfer decider<br/>and Postgres
    end
    participant CH as Clearing house
    participant API as Inbound API<br/>/api/clearing/inbound

    WF->>LA: reserveForExternalTransfer
    LA->>DB: RESERVED: customer → clearing account
    Note over WF: confirmation expected from now on<br/>(it may arrive before the next step returns)
    WF->>CA: submit (retried until the 10-second deadline)
    CA->>CH: POST pacs.008 (acceptance time, UETR = transfer ID)
    CH-->>CA: 202 (409: an earlier attempt got through)
    WF->>LA: markSentToExternalBank
    LA->>DB: SENT_TO_EXTERNAL_BANK

    alt confirmation within 20 seconds
        CH->>API: POST pacs.002 ACSC or RJCT
        API->>WF: update "confirm" (validator first)
    else no confirmation
        loop every 30 s, then under investigation every 10 min
            WF->>CA: inquire
            CA->>CH: POST pacs.028
            CH-->>CA: pacs.002 ACSC, RJCT or PDNG, or 404 (unknown)
        end
        Note over WF,DB: after 5 minutes without a final status: UNDER_INVESTIGATION,<br/>the amount stays reserved. A late confirmation still ends the wait.
    end

    alt ACSC
        WF->>LA: settleExternalTransfer
        LA->>DB: COMPLETED: clearing account → settlement account
    else RJCT
        WF->>LA: refundExternalTransfer("Account closed (AC04)")
        LA->>DB: REFUNDED: clearing account → customer
    end
```

When is an answer final?

| Answer | Final? | Then |
| --- | --- | --- |
| `pacs.002` ACSC (confirmation or inquiry) | yes | settle: the reserved amount goes to the settlement account |
| `pacs.002` RJCT with a reason code | yes | refund, with the reason as text: "Account closed (AC04)" |
| the clearing house refuses the `pacs.008` (4xx other than 409) | yes, it recorded nothing | refund at once |
| unknown at the inquiry, and it never confirmed receipt | yes: the deadline has passed, it refuses the payment if it still arrives | refund |
| unknown although it confirmed receipt, PDNG, not reachable | no | keep asking; after 5 minutes `UNDER_INVESTIGATION` |

So no path releases or books the money without a definitive answer.

The inbound endpoint (`POST /api/clearing/inbound/pacs002`) trusts whoever calls it, like every endpoint of the lab
so far. Between real banks and their clearing house this connection is mutually authenticated (mTLS on a private
network); here a forged ACSC could settle a payment. It belongs to the authentication work in #36. The clearing house counts its deadline from the
payment's acceptance time (`acceptanceDateTime`, ISO 20022 AccptncDtTm) and refuses a `pacs.008` that reaches it
later; that is what makes "unknown after the deadline" final.

Transfers whose workflow reached its external part before #59 continue with the simulated external bank:
`Workflow.getVersion("sepa-instant", …)` sends executions without the version marker down the old branch. The replay
test replays the recorded histories of both versions (`v1-*`, `v2-*`). Once no old execution is open any more, the old
branch and the simulator can go.

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
- System accounts: cash (`…0001`) for deposits, clearing account for amounts reserved for external transfers
  (`…0002`), settlement account (`…0003`), which mirrors the bank's account at the clearing house: a settled payment
  moves from the clearing account to it. They may go negative and have no IBAN.
- Every customer account has an IBAN of this bank: `DE`, check digits, the bank code from `bank.identity` and a
  10-digit account number from a sequence. The `Iban` value object in `domain` validates and builds it; its
  `toString()` masks it, so it doesn't end up in logs by accident.

## One service, several banks

The same bank-service runs as bank A (defaults) and bank B (profile `bank-b`): own identity (`bank.identity`: name,
BIC, bank code), own Postgres schema, own Temporal namespace, own `service.name` (`bank-a`, `bank-b`) in the
telemetry. `BankInstancesTest` checks that the two configurations share nothing but the containers.

## The clearing house

A separate service (`clearing`, port 8090) between the banks, modelled on a SEPA Instant clearing and settlement
mechanism (#55). It has its own Postgres schema and Temporal namespace (`clearing`) and the same split as the banks
([decision 0007](decisions/0007-workflow-runs-the-process-domain-decides-and-records.md)):

- **Books:** a settlement account per participant (central bank money, funded at startup), a `reserved` account for
  payments in flight, double-entry postings per payment. The payment decider decides every status change.
- **One workflow per payment** (`payment-<uetr>`):
  1. reserve the amount on the debtor bank's settlement account; without enough liquidity: rejected (`AM04`);
  2. forward the `pacs.008` to the creditor bank, routed by the bank code in the IBAN (unknown bank: `RC01`);
  3. wait for its `pacs.002` (an update) until the deadline: 10 seconds from the payment's acceptance at the debtor
     bank. A `pacs.008` that arrives after it is refused (422) and not recorded;
  4. on acceptance settle (reserved → creditor bank), otherwise release the reservation (the creditor's reason code,
     or `AB05` without an answer in time);
  5. confirm the result to both banks.
- **API** (`/api/clearing`): `pacs008` from debtor banks, `pacs002` answers from creditor banks, `pacs028` status
  inquiries, and for operations the payments, the settlement balances and chaos settings (delayed or lost forwards).
- Banks receive messages at `POST /api/clearing/inbound/pacs002` (the confirmations of their own payments, #59) and
  `…/pacs008` (incoming payments, #60).

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
