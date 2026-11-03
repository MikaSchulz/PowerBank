# Contributing

## One feature, one branch, one pull request

1. An issue describes the feature or the bug.
2. Branch from an up-to-date `main`: `feature/<short-name>` (or `fix/<short-name>`).
3. Commit in small steps, with English messages in the imperative ("Add …", "Fix …").
4. Open a pull request against `main`. The template asks how it was tested and for the privacy check. Link the
   issue (`Closes #…` or `Part of #…`).
5. CI must be green and the branch up to date with `main`; use *Update branch* when GitHub asks for it.
6. Merge with a merge commit. GitHub then deletes the branch, and the next feature starts from `main` again.

`main` is protected by the ruleset in [`.github/rulesets/main.json`](.github/rulesets/main.json) (Settings → Rules →
Rulesets → Import): pull requests only, the check "Build and test (Java 25)" must pass, the branch must be up to
date, conversations resolved, merge commits only, no force pushes, no deletion. In the repository settings,
*Automatically delete head branches* is on and only merge commits are allowed.

## Local setup

```bash
docker compose up -d --wait
```

```bash
./gradlew :bank-service:bootRun
```

```bash
docker compose down
```

`./gradlew test` needs no containers. `./gradlew integrationTest` needs the running stack. Details in the
[local setup](docs/local-setup.md).

## Rules

- **English everywhere:** code, comments, docs, issues, pull requests, commit messages.
- **No personal data in telemetry:** logs, spans, metric labels, workflow IDs and error messages only contain
  pseudonymous IDs (transfer, account). Never names, IBANs, amounts or balances.
- **Architecture rules are tests** (`ArchitectureTest`): workflow code is deterministic and only uses Temporal and
  its activity contracts, the domain knows neither Spring nor Temporal nor the database, the ledger doesn't know
  who calls it, controllers don't talk SQL.
- **Database changes only as new Flyway migrations.** Never edit a migration that has been applied; add the next
  `V<n>__…sql` instead.

## Dependencies

Dependabot opens pull requests: weekly for Gradle, GitHub Actions and the Docker images in `compose.yaml`, monthly
for npm. Versions and images are pinned and only change through these pull requests.

The OpenTelemetry bundle for the browser is vendored (`static/vendor/otel-web.js`). After an npm update, rebuild it
on the update's branch and commit the result:

```bash
cd bank-service/src/main/ui-telemetry && npm ci && npm run build
```

CI rebuilds the bundle and fails if the committed file doesn't match `package-lock.json`.
