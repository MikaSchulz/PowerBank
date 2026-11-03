# 0004: No personal data in telemetry

**Status:** accepted, 2026-11

## Context
Logs, traces and metrics are read by developers and operations and kept outside the bank's own data. A bank's
customer data (names, IBANs, amounts, balances) doesn't belong there, also not by accident in an error message.

## Decision
- Logs, spans, metric labels, workflow IDs and error messages only contain **pseudonymous IDs** (transfer, account).
  Never names, IBANs, amounts or balances. Metrics only contain aggregates.
- Nicknames like "Alice" only live in the browser (localStorage); the bank only knows IDs.
- The database's own logs pass through an allowlist in the collector, because Postgres' JSON log always writes the
  failing row (DETAIL). Error messages of triggers name the transfer, not the amount (migration V4).
- The pull request template asks for this check on every change.

## Consequences
- Dashboards and logs can be shown to anyone working on the system.
- Finding a transfer's data means looking it up in the ledger by its ID.
- Still open: the external IBAN and the amount are part of the workflow input and visible in the Temporal UI until
  stage 9 encrypts payloads ([roadmap](../roadmap.md)).
