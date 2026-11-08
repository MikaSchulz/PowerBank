# Learnings

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
- **Temporal target:** `localhost:7233` instead of `local` (see [architecture](architecture.md#temporal-connection)).
- **Observability:** Grafana LGTM in `compose.yaml` and OpenTelemetry for traces, metrics and logs (not in the
  original plan).

## Known learning points

- **Unclear is not rejected:** before SEPA Instant (#59) the workflow refunded the debit after 10 minutes without an
  answer from the simulated external bank, although the bank might have booked it after all. Now the money moves only
  on a final status: a confirmation, an answer to a status inquiry, or "unknown" after the deadline, which is only
  final because the clearing house refuses a payment that arrives late. Everything else is investigated, not guessed.
- **A changed workflow with executions in flight:** the external part of the transfer workflow changed with #59.
  `Workflow.getVersion` keeps executions from before on the old branch; the replay test proves it with the recorded
  `v1` histories, and fails without the version check. The old branch and the simulator go once no old execution is
  open any more.
- **Hot rows:** every deposit locks the cash account, every external transfer locks the clearing account. System
  accounts wouldn't need a lock (they have no funds check) and no balance fold either. A candidate for the balance snapshot.
- **Two stores, one truth:** the ledger lives in Postgres, the running transfers in Temporal. Locally both are on
  volumes and reset together (`docker compose down -v`); until [#40](https://github.com/MikaSchulz/PowerBank/issues/40) brought the volume, the dev server kept
  workflows in memory and lost them on every restart. If the two ever get out of sync, transfers stay in an
  unfinished status (received, awaiting approval, reserved, sent to the external bank) without a workflow: the dashboard shows
  them under *Oldest waiting transfer* and the alerts fire. A
  reconciliation job that finds them is planned in [#40](https://github.com/MikaSchulz/PowerBank/issues/40); in production Temporal needs its own database ([#36](https://github.com/MikaSchulz/PowerBank/issues/36)).
- **IBAN in the workflow:** for external transfers, the creditor IBAN is part of the workflow input. Stage 9 encrypts it
  with the payload codec. Even more data-minimizing: a payee ID that only the activity resolves.
- **Changing a workflow strands the waiting ones:** moving the transfer's statuses into the ledger
  ([decision 0007](decisions/0007-workflow-runs-the-process-domain-decides-and-records.md)) changed the steps of the
  transfer workflow. Transfers that were still running before (e.g. waiting for an approval) can't be replayed with the
  new code; locally they are terminated or the stack is reset. Since then such changes need `Workflow.getVersion`
  (stage 11, [#16](https://github.com/MikaSchulz/PowerBank/issues/16)); #59 is the first one that uses it.
- **A computed property on an activity DTO breaks the replay:** a `val rejected get() = …` on the activity result was
  serialized by Jackson into the history and then couldn't be read back, so the workflow task failed and retried
  forever; the workflow test hung instead of failing. Helpers on DTOs go into extension functions in the workflow.
