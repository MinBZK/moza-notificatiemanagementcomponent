# ZAD PR-deploy

`deploy.yml` bouwt per Pull Request een container-image en deployt die naar een
ephemeral ZAD-omgeving (`pr-<nummer>`). Bij sluiten van de PR ruimt
`cleanup-preview` die weer op; zie [Opruimen](#opruimen).

> **Scope:** ZAD is de **PR-preview**- en ontwikkelomgeving: PR-previews en `stable` in
> `nd-j7s`, en `stable` en `pentest` in het tst-project `nt-8bl`. Echte releases draaien
> op een ander cluster. De env-vars uit dit document (datasource, notify, pepper)
> zijn nodig op elke deployomgeving; op ZAD staan ze op de `feature`-deployment,
> op het release-cluster op de eigen deploymentconfig.

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
| `ZAD_API_KEY_TST` | Idem voor het tst-project `nt-8bl` (alleen de `stable`-deploy) |
| `GITHUB_ADMIN_TOKEN` | Token met "Administration: write" op deze repo, om GitHub-environments `pr-<n>` te verwijderen. **Bestaat nog niet**; zie [Opruimen](#opruimen) |

`GITHUB_TOKEN` is automatisch beschikbaar (image push naar GHCR, GitHub-deployments).

### 2. Applicatieconfiguratie in ZAD (managed DB + secrets)

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
| `NOTIFY_API_KEY` | NotifyNL-integratie. De template-ID's staan in de code (`BerichtType`), niet in de config |
| `NOTIFY_CALLBACK_BEARER_TOKEN` | Bearer token waarmee NotifyNL de callback aanroept; leeg laat de app niet starten |
| `QUARKUS_REST_CLIENT_NOTIFY_URL` | NotifyNL-endpoint; `application.properties` heeft geen default (leeg buiten `%test`), dus verplicht per deployomgeving |
| `QUARKUS_REST_CLIENT_PROFIELSERVICE_URL` | Profielservice-endpoint; idem, geen default, verplicht per deployomgeving |
| `HASH_PEPPER` | Keyed HMAC pepper (mag niet leeg in prod) |
| `LOGBOEKDATAVERWERKING_ENABLED` | Verwerkingenlogging (LDV). `%prod` laat 'm leeg, dus verplicht per deployomgeving. **Voorlopig `false`**; op `true` zetten zodra de ClickHouse-config hieronder werkt |
| `LOGBOEKDATAVERWERKING_CLICKHOUSE_ENDPOINT`, `_USERNAME`, `_PASSWORD`, `_DATABASE`, `_TABLE` | ClickHouse-sink voor LDV. Alleen nodig zodra `LOGBOEKDATAVERWERKING_ENABLED=true`; nu leeg te laten |

> De drie `QUARKUS_DATASOURCE_*` zijn geen env-vars maar **aliassen** op component `nmcapi`
> (`zadctl alias list nmcapi`). Ze vertalen de variabelen die de `postgresql-database`-service
> injecteert: `QUARKUS_DATASOURCE_USERNAME=$APP_DATABASE_USER`,
> `QUARKUS_DATASOURCE_PASSWORD=$APP_DATABASE_PASSWORD` en
> `QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://$APP_DATABASE_SERVER_HOST:$APP_DATABASE_PORT/$APP_DATABASE_DB`.
> Ontbreken ze, dan probeert de app `localhost:5432` en faalt Flyway bij het starten.

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
- PR closed (gemerged of niet) → `cleanup-preview`, via `pull_request_target`: een
  `pull_request`-run start niet voor een PR met een mergeconflict. Die run gebruikt de
  workflow van de base-branch, dus een gestapelde PR ruimt pas goed op als zijn base
  deze versie van `deploy.yml` heeft.

URL-patroon: `https://nmcapi-pr-<n>-nd-j7s.rig.prd1.gn2.quattro.rijksapps.nl`
→ `/q/swagger-ui`, `/q/openapi`, `/q/health/ready`.

## Tst-project (`nt-8bl`)

Naast `nd-j7s` (NMC - dev) bestaat het ZAD-project `nt-8bl` (NMC - tst), met dezelfde opbouw:
component `nmcapi`, `postgresql-database`, `publish-on-web` en de datasource-aliassen. De
env-vars zijn de component-brede van `nd-j7s`, dus dezelfde als de PR-deploys krijgen, niet
de `stable`-overrides. De `notify-cert`-bijlage ontbreekt bewust: de NMC praat hier met een
WireMock en `QUARKUS_TLS_NOTIFY_TRUST_ALL` staat aan. Er zijn twee deployments:

| Deployment | Wordt bijgewerkt door |
| --- | --- |
| `stable` | `deploy-stable-tst` in `deploy.yml`, bij elke push naar `main` (secret `ZAD_API_KEY_TST`) |
| `pentest` | Alleen met de hand: Actions → **ZAD Deploy pentest** (`deploy-pentest.yml`) → *Run workflow*. Bouwt niets: zet het image `main-<sha7>` van de laatste geslaagde main-run van `deploy.yml` op digest op `pentest` |

URL-patroon: `https://nmcapi-<deployment>-nt-8bl.rig.prd1.gn2.quattro.rijksapps.nl`.

## Opruimen

Twee workflows ruimen een preview op. Beide verwijderen de ZAD-deployment, de
GitHub-deployments, het image `pr-<n>` in GHCR en het GitHub-environment `pr-<n>`.

| Workflow | Wanneer |
| --- | --- |
| `cleanup-preview` in `deploy.yml` | Bij sluiten van de PR |
| `zad-scheduled-cleanup.yml` | Dagelijks om 03:17 UTC, en met de hand via *Run workflow* (standaard als dry-run). Vangnet voor elk environment `pr-<n>` waarvan de PR niet meer open is, bijvoorbeeld omdat `cleanup-preview` niet startte of faalde |

Het GitHub-environment verwijderen vraagt "Administration: write" op de repo. Dat recht
kan `GITHUB_TOKEN` in een workflow niet krijgen, dus beide workflows gebruiken daarvoor
het secret `GITHUB_ADMIN_TOKEN`. Zolang dat ontbreekt, slaan ze alleen die stap over en
blijven de environments staan. De geplande workflow vindt achtergebleven previews via die
environments en loopt ze dan bij elke run opnieuw langs.

`GITHUB_ADMIN_TOKEN` moet een repo-admin aanmaken: bij voorkeur een token van een GitHub
App met alleen "Administration: write" op deze repo, anders een fine-grained PAT met
hetzelfde recht, beperkt tot deze repo.

## Gotchas (al opgelost in de workflow)

- **JDBC-URL** moet volledig zijn: `jdbc:postgresql://host:5432/<db>` (env op
  `feature/nmcapi`), niet enkel host of `jdbc://...`.
- **Image-digest i.p.v. mutable `pr-N` tag**: ZAD/k8s pullt een gelijke tag niet
  opnieuw, waardoor image-wijzigingen niet live kwamen. Deploy gaat nu op digest.
- **Swagger UI** alleen in preview-image via de Maven-flag
  `-Dquarkus.swagger-ui.always-include=true`; prod-build blijft schoon.

## Restpunten

- [ ] `GITHUB_ADMIN_TOKEN` als repo-secret toevoegen (zie [Opruimen](#opruimen)). Daarna
  ruimt de eerste run van `zad-scheduled-cleanup.yml` de bestaande environments van
  gesloten PR's op.
