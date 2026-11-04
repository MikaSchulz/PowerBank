# 0007: What waits in a workflow and what is domain state

**Status:** accepted, 2026-11

## Context
The business statuses of a transfer (awaiting approval, at the external bank, …) used to exist only inside the
workflow. The ledger only knew whether something had been booked. Which status change is allowed was implicit in the
workflow code, and while a transfer waited days for an approval, the waiting was invisible to SQL, dashboards and
alerts. The interbank payments to come ([#55](https://github.com/MikaSchulz/PowerBank/issues/55)) add more statuses
and longer waits, so the boundary between workflow and business has to be explicit.

## Decision
- **The domain decides and records.** The business rules and the business state live in the domain: the
  **transfer decider** (fmodel) defines the transfer's statuses and which change is allowed, the account decider
  whether money may move. The ledger stores the status in `transfers.status` and every change in `transfer_events`.
  It is queryable with SQL at any time and outlives Temporal's retention.
- **The workflow coordinates over time.** It decides *when* a step is attempted and *what happens if it doesn't
  work*: order of the steps, retries, deadlines, waiting, compensation. Every step is an activity that runs a
  command through the deciders; the workflow mirrors the status the ledger returns and never sets one itself.
- **A workflow waits only for something with a deadline** the system has to enforce: an approval (3 days), later the
  answer of the clearing house (10 seconds) or of a recall (days). While it waits, the status is already in the ledger,
  so nothing is hidden in Temporal. Waiting costs nothing at runtime: Temporal keeps the open workflow and a timer, no
  thread runs.
- **Without a deadline, no waiting workflow.** Events that may come at any time or never (an incoming payment, a late
  return) start their own short workflow and find their transfer by its ID.
- **Recurring work** uses schedules that start short workflows ([#1](https://github.com/MikaSchulz/PowerBank/issues/1)),
  not one workflow that lives forever.

## Consequences
- The status rules are tested without infrastructure (`TransferDeciderTest`); the workflow tests only test the
  orchestration.
- The API reads statuses, listings and the history from the ledger; the workflow's `status` query remains for the
  Temporal UI. The UI shows the history, the dashboards and alerts see `AWAITING_APPROVAL` directly.
- The workflow's status enum is a contract stored in workflow histories; it mirrors the domain's by name, checked by
  `TransferStatusContractTest`.
- **The cost of long waits is versioning.** Changing the steps of a workflow while executions wait breaks their
  replay. This change itself is such a step: transfers still running from before can't continue and have to be
  terminated or the local stack reset. From now on, changes to waiting workflows need `Workflow.getVersion` and
  replay tests with recorded histories (stage 11, [#16](https://github.com/MikaSchulz/PowerBank/issues/16)).
