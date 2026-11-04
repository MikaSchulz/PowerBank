# Architecture decisions

Short records of decisions that shape the code: what was decided, why, and what follows from it. Read them before
changing something they cover.

| No. | Decision | Status |
| --- | --- | --- |
| [0001](0001-ledger-is-the-source-of-truth.md) | The ledger is the source of truth, Temporal orchestrates | accepted |
| [0002](0002-account-decider-over-postings.md) | Account logic as an fmodel decider; postings are its events | accepted |
| [0003](0003-workflows-only-know-activity-contracts.md) | Workflows only know Temporal and activity contracts | accepted |
| [0004](0004-no-personal-data-in-telemetry.md) | No personal data in telemetry | accepted |
| [0005](0005-observability-with-opentelemetry-and-grafana-lgtm.md) | Observability with OpenTelemetry and Grafana LGTM | accepted |
| [0006](0006-local-stack-by-hand-and-two-test-suites.md) | Local stack by hand, two test suites | accepted |
| [0007](0007-workflow-runs-the-process-domain-decides-and-records.md) | The workflow runs the business process; the domain decides and records | accepted |

**Writing one:** copy the structure (Status, Context, Decision, Consequences), take the next number, keep it to a
page. An accepted record isn't rewritten; a new decision that replaces it gets a new record, and the old one's status
becomes "superseded by …".
