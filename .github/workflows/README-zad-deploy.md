# ZAD PR-deploy

`deploy.yml` bouwt per Pull Request een container-image en deployt die naar een
ephemeral ZAD-omgeving (`pr-<nummer>`). Bij sluiten van de PR ruimt
`cleanup-preview` de omgeving, het image en het GitHub-environment op.

## Eenmalige setup

### 1. Repo-secrets

| Secret | Waarvoor |
| --- | --- |
| `ZAD_API_KEY` | ZAD Operations Manager API key (deploy + cleanup) |
| `GITHUB_ADMIN_TOKEN` | PAT met repo-admin; nodig om het GitHub-environment te verwijderen bij cleanup |

`GITHUB_TOKEN` is automatisch beschikbaar (image push naar GHCR).

### 2. `project-id` invullen

In `deploy.yml` staat `ZAD_PROJECT_ID: PLACEHOLDER-PROJECT-ID`. Vervang door de
echte ZAD project-id.

### 3. Applicatieconfiguratie in ZAD (managed DB + secrets)

De `zad-actions/deploy` action zet **geen** env-vars of DB-config; de app krijgt
die uit de ZAD-deploymentconfig. Dit is geregeld via de **`feature`-deployment**
(component `nmcapi`), waar `clone-from: feature` in `deploy.yml` naar verwijst.
PR-deploys erven die env.

Benodigde env-vars op die deployment:

| Env var | Reden |
| --- | --- |
| `DB_USERNAME`, `DB_PASSWORD` | Datasource-credentials (managed Postgres) |
| `QUARKUS_DATASOURCE_JDBC_URL` | JDBC-url naar de managed Postgres |
| `QUARKUS_FLYWAY_MIGRATE_AT_START` = `true` | Container draait prod-profiel waar dit `false` is; zonder migraties faalt boot op `schema-management=validate` |
| `NOTIFY_API_KEY`, `NOTIFY_TEMPLATE_ID` | NotifyNL-integratie |
| `HASH_PEPPER` | Keyed HMAC pepper (mag niet leeg in prod) |

> Quarkus mapt env-vars naar properties: `QUARKUS_FLYWAY_MIGRATE_AT_START`
> overschrijft `%prod.quarkus.flyway.migrate-at-start`. `DB_USERNAME`/`DB_PASSWORD`
> worden al door `application.properties` ingelezen (`${DB_USERNAME:}`).

## Hoe het draait

- PR open/synchronize/reopen → `build` (image `pr-<n>` naar GHCR) → `deploy-preview`
  (deploy + wacht op `/q/health/ready` + plaatst deploy-URL als PR-comment).
- PR closed → `cleanup-preview`.

URL-patroon: `https://nmcapi-pr-<n>-nd-j7s.rig.prd1.gn2.quattro.rijksapps.nl`
