# 0001: The ledger is the source of truth, Temporal orchestrates

**Status:** accepted, 2026-11

## Context
Money must never appear or disappear. Workflows are retried and replayed, activities can run more than once, and a
workflow's history is a log of steps, not a bookkeeping system.

## Decision
- Postgres holds the **ledger**: double-entry bookkeeping with immutable postings. A transfer's postings sum up to
  zero, and an account's balance is the sum of its postings, never a stored number.
- The database enforces it: a trigger forbids UPDATE and DELETE on postings, and a deferred constraint trigger checks
  at commit that every transfer is balanced.
- Every booking is idempotent per transfer ID: the first decision stands, also across activity retries, backed by
  the unique constraint `(transfer_id, account_id, leg)`.
- **Temporal orchestrates**: approvals, the external bank saga, retries and timeouts. It calls activities that book,
  but it never holds balances.

## Consequences
- Bookings stay correct even when a workflow misbehaves or runs twice.
- The ledger can be checked on its own: the gauge `bank_ledger_sum_cents` and the critical alert "Ledger
  unbalanced" watch the sum of all postings.
- Two stores have to stay in step: transfers in the ledger and running workflows in Temporal ([0006](0006-local-stack-by-hand-and-two-test-suites.md), [#40](https://github.com/MikaSchulz/PowerBank/issues/40)).
- Balances are folded from postings on every booking; a snapshot comes once accounts get many postings ([#22](https://github.com/MikaSchulz/PowerBank/issues/22)).
