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

- **External bank unreachable:** after 10 minutes the workflow refunds the debit. In real life it's then unclear
  whether the external bank booked the payment after all; you'd need a status inquiry. The simulator is idempotent per
  transfer ID, but forgets its decisions on restart.
- **Hot rows:** every deposit locks the cash account, every external transfer locks the clearing account. System
  accounts wouldn't need a lock (they have no funds check) and no balance fold either. A candidate for the balance snapshot.
- **Two stores, one truth:** the ledger lives in Postgres, the running transfers in Temporal. Locally both are on
  volumes and reset together (`docker compose down -v`); until [#40](https://github.com/MikaSchulz/PowerBank/issues/40) brought the volume, the dev server kept
  workflows in memory and lost them on every restart. If the two ever get out of sync, transfers stay PENDING or
  DEBITED without a workflow: the dashboard shows them under *Oldest waiting transfer* and the alerts fire. A
  reconciliation job that finds them is planned in [#40](https://github.com/MikaSchulz/PowerBank/issues/40); in production Temporal needs its own database ([#36](https://github.com/MikaSchulz/PowerBank/issues/36)).
- **IBAN in the workflow:** for external transfers, the creditor IBAN is part of the workflow input. Stage 9 encrypts it
  with the payload codec. Even more data-minimizing: a payee ID that only the activity resolves.
