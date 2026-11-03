# 0002: Account logic as an fmodel decider; postings are its events

**Status:** accepted, 2026-11

## Context
The rules of an account (funds, overdraft, blocked or closed accounts, reversals) should be testable without a
database, Spring or Temporal, and readable in one place.

## Decision
- The `domain` module holds the account as an **fmodel decider**: `decide(command, state)` returns events,
  `evolve(state, event)` builds the state. It depends only on Kotlin, coroutines and fmodel.
- The **postings are the account's events**: `AccountEventRepository` loads them, the decider decides, new postings
  are saved. Opening, blocking and closing are decider events too, stored as the current status in `accounts.status`.
- Concurrency: the account rows are locked with `SELECT … FOR UPDATE`, always sorted by ID, so two transfers between
  the same accounts can't deadlock.

## Consequences
- The rules are tested with Given/When/Then and no infrastructure (`AccountDeciderTest`).
- `ArchitectureTest` keeps the domain free of Spring, Temporal and the database.
- Every deposit locks the cash account and every external transfer the clearing account: hot rows under load (see
  [learnings](../learnings.md)).
