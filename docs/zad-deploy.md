# ZAD PR-deploy

`deploy.yml` bouwt per Pull Request een container-image en deployt die naar een
ephemeral ZAD-omgeving (`pr-<nummer>`). Bij sluiten van de PR ruimt
`cleanup-preview` de omgeving, het image en het GitHub-environment op.

Issue: [#749](https://github.com/MinBZK/MijnOverheidZakelijk/issues/749).
ZAD-project: `nd-j7s`.

## Hoe ZAD hier werkt

De `RijksICTGilde/zad-actions/deploy` action zet alleen **welk container-image** +
optioneel **`clone-from`** (config erven van een bestaande deployment). De action
zet **geen** app-config (DB-url, wachtwoorden, `hash.pepper`, notify-keys). Die
config leeft in de deployment zelf, ingesteld in de ZAD **Operations Manager**.
Een PR-deploy erft die via `clone-from feature`.

→ Gevolg: er moet **eenmalig** een base-deployment met de juiste env bestaan,
anders start de app zonder DB en gaat de deploy rood (`/q/health/ready` faalt).

## Eenmalige setup

### 1. Repo-secrets

| Secret | Waarvoor |
| --- | --- |
| `ZAD_API_KEY` | ZAD Operations Manager API key (deploy + cleanup) |
| `GITHUB_ADMIN_TOKEN` | PAT met repo-admin; nodig om het GitHub-environment te verwijderen bij cleanup |

`GITHUB_TOKEN` is automatisch beschikbaar (image push naar GHCR).

### 2. `project-id` invullen

In `deploy.yml` staat `ZAD_PROJECT_ID`. Vul de echte ZAD project-id in (`nd-j7s`).

### 3. Applicatieconfiguratie in ZAD (managed DB + secrets)

De `zad-actions/deploy` action zet **geen** env-vars of DB-config; de app krijgt
die uit de ZAD-deploymentconfig. Dit is geregeld via de **`feature`-deployment**
(component `nmcapi`), waar `clone-from: feature` in `deploy.yml` naar verwijst.
PR-deploys erven die env.

Benodigde env-vars op die deployment:

| Env var | Reden |
| --- | --- |
| `QUARKUS_DATASOURCE_USERNAME`, `QUARKUS_DATASOURCE_PASSWORD` | Datasource-credentials (managed Postgres) |
| `QUARKUS_DATASOURCE_JDBC_URL` | JDBC-url naar de managed Postgres |
| `QUARKUS_FLYWAY_MIGRATE_AT_START` = `true` | Container draait prod-profiel waar dit `false` is; zonder migraties faalt boot op `schema-management=validate` |
| `NOTIFY_API_KEY`, `NOTIFY_TEMPLATE_ID` | NotifyNL-integratie |
| `HASH_PEPPER` | Keyed HMAC pepper (mag niet leeg in prod) |

> Quarkus mapt env-vars naar properties via name-mangling (uppercase, niet-alfanumeriek
> → `_`): `QUARKUS_DATASOURCE_USERNAME`/`_PASSWORD`/`_JDBC_URL` vullen de datasource-config,
> `QUARKUS_FLYWAY_MIGRATE_AT_START` overschrijft `%prod.quarkus.flyway.migrate-at-start`.
> `application.properties` bevat hiervoor geen placeholders meer — alle prod-config loopt
> uniform via deze `QUARKUS_*`-env-vars.

> Deze env-vars worden nu handmatig gezet. Automatiseren via de ZAD Operations
> Manager API staat open als [#10](https://github.com/MinBZK/moza-notificatiemanagementcomponent/issues/10).

## Hoe het draait

- PR open/synchronize/reopen → `build` (image `pr-<n>` naar GHCR) → `deploy-preview`
  (deploy + wacht op `/q/health/ready` + plaatst deploy-URL als PR-comment).
- PR closed → `cleanup-preview`.

URL-patroon: `https://nmcapi-pr-<n>-nd-j7s.rig.prd1.gn2.quattro.rijksapps.nl`
→ `/q/swagger-ui`, `/q/openapi`, `/q/health/ready`.

## Gotchas (al opgelost in de workflow)

- **JDBC-URL** moet volledig zijn: `jdbc:postgresql://host:5432/<db>` (env op
  `feature/nmcapi`), niet enkel host of `jdbc://...`.
- **Image-digest i.p.v. mutable `pr-N` tag**: ZAD/k8s pullt een gelijke tag niet
  opnieuw, waardoor image-wijzigingen niet live kwamen. Deploy gaat nu op digest.
- **Swagger UI** alleen in preview-image via de Maven-flag
  `-Dquarkus.swagger-ui.always-include=true`; prod-build blijft schoon.

## Restpunten

- [ ] `GITHUB_ADMIN_TOKEN` (repo-admin PAT) als repo-secret toevoegen, daarna in
  `deploy.yml` `delete-github-env` + `delete-github-deployments` weer op `'true'`
  zetten (nu `'false'` om hard falen van cleanup te voorkomen).
