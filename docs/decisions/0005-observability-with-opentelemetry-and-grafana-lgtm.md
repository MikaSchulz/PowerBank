# 0005: Observability with OpenTelemetry and Grafana LGTM

**Status:** accepted, 2026-11

## Context
A transfer crosses the browser, the REST API, Temporal, activities, the database and an external bank. Following one
transfer end to end needs one trace ID through all of them, plus metrics and logs that point to it.

## Decision
- **OpenTelemetry (OTLP)** for traces, metrics and logs, from the bank-service and from the browser. Locally it all
  goes to the `grafana/otel-lgtm` container (Tempo, Prometheus, Loki, Grafana).
- **W3C trace context** end to end: the UI starts a trace per user action, Spring continues the `traceparent`, and
  Temporal carries it in the workflow header to every activity.
- The browser sends its telemetry through a proxy in the bank-service (`/api/otel/v1/…`) instead of reaching the
  collector directly.
- Temporal server and Postgres are collected by a second config file for the collector inside the LGTM container.
- Dashboards and alert rules are files in the repository (`observability/grafana`), provisioned on every start.

## Consequences
- Every transfer is one trace from the click to the SQL; dashboards and alerts are reviewed like code.
- The LGTM container is for local use. Production needs its own telemetry backend and login in front of the browser
  proxy ([#36](https://github.com/MikaSchulz/PowerBank/issues/36)).
- The collector overlay relies on names from the LGTM image's config; updating the image means checking them.
