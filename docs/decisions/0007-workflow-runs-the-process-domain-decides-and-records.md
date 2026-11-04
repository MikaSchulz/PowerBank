# 0007: The workflow runs the business process; the domain decides and records

**Status:** accepted, 2026-11

## Context
The business statuses of a transfer (awaiting approval, at the external bank, …) used to exist only inside the
workflow. The ledger only knew whether something had been booked. Which status change is allowed was implicit in the
workflow code, and while a transfer waited days for an approval, the waiting was invisible to SQL, dashboards and
alerts. The interbank payments to come ([#55](https://github.com/MikaSchulz/PowerBank/issues/55)) add more statuses
and longer waits, so the boundary between workflow, activities and domain has to be explicit.

## Decision
| Question | Answer |
| --- | --- |
| What is a workflow? | **One instance of a business process**, from its trigger to its end, including every wait: for a person, another system or time. A transfer, an account opening, a recall. Not one workflow per REST call. |
| Where is the business state? | **In the ledger** (Postgres): status, decisions, history. The workflow writes it through activities and mirrors it; Temporal never holds the only copy. |
| Where do technical retries go? | **Into activities**, with retry policies and idempotent implementations. A call that may fail is an activity, not a workflow of its own. |
| Where are the business rules? | **In the domain** (fmodel deciders), run by the ledger inside activities. Never in workflow code. |
| When a new workflow instead of waiting? | When an event belongs to **no running process**: an incoming payment, for example, starts its own workflow. A running process waits for what it expects, with a durable timer as its deadline. |

For a transfer: the transfer decider defines the statuses and which change is allowed, the account decider whether
money may move. The ledger stores the status in `transfers.status` and every change in `transfer_events`. The
`TransferWorkflow` is the process: it decides when a step is attempted, waits for the approval (update plus a 3-day
timer), retries and compensates, and mirrors the status each ledger activity returns.

Waiting in a workflow is Temporal's standard pattern for human-in-the-loop steps (signals or updates with a durable
timer). It costs nothing at runtime: no thread runs, Temporal keeps the open workflow and the timer.

### Considered: business status in the database, workflows only for technical retries
That is how such systems are built without Temporal. The waiting then has to be built by hand: a job that finds expired
approvals, a handler that continues the process after an approval, locking for an approval that arrives just as the
deadline expires, and the process spread over API handlers, jobs and workflows. Temporal's updates and durable timers
cover exactly that, so we use them; the business state is in the database either way.

## Consequences
- The status rules are tested without infrastructure (`TransferDeciderTest`); the workflow tests only test the
  orchestration.
- The API reads statuses, listings and the history from the ledger; the workflow's `status` query remains for the
  Temporal UI. The UI shows the history, the dashboards and alerts see `AWAITING_APPROVAL` directly.
- [Architecture](../architecture.md#a-transfer-with-an-approval-step-by-step) shows one transfer as a sequence diagram
  across UI, workflow, activities and domain.
- The workflow's status enum is a contract stored in workflow histories; it mirrors the domain's by name, checked by
  `TransferStatusContractTest`.
- **The cost of long waits is versioning.** Changing the steps of a workflow while executions wait breaks their
  replay. This change itself is such a step: transfers still running from before can't continue and have to be
  terminated or the local stack reset. From now on, changes to waiting workflows need `Workflow.getVersion` and
  replay tests with recorded histories (stage 11, [#16](https://github.com/MikaSchulz/PowerBank/issues/16)).
  Processes that would grow without end use continue-as-new or schedules with short workflows
  ([#1](https://github.com/MikaSchulz/PowerBank/issues/1)).
