# 0003: Workflows only know Temporal and activity contracts

**Status:** accepted, 2026-11

## Context
Temporal replays workflow code from its history. Anything non-deterministic (clock, random, threads, I/O) or any
direct use of infrastructure in a workflow breaks the replay, often only after a restart in production.

## Decision
- Workflow code only depends on Temporal and the activity interfaces. It never touches the ledger, the API, Spring,
  JDBC, coroutines or fmodel.
- Time, randomness and sleeping only through Temporal (`Workflow.currentTimeMillis()`, `Workflow.randomUUID()`,
  `Workflow.sleep()`); logs through `Workflow.getLogger`, metrics through `Workflow.getMetricsScope()`.
- Activities take small dedicated DTOs (e.g. `BookingOutcome`) instead of the domain's commands and events, so
  Temporal doesn't serialize polymorphic types.

## Consequences
- `ArchitectureTest` enforces both rules, and a deliberately broken fixture (`NonDeterministicWorkflow`) proves that
  they fire.
- Workflow logs and metrics aren't duplicated when a workflow is replayed.
- Changing a workflow's logic for running workflows needs versioning (stage 11).
