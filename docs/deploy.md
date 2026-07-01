# ZAD PR-deploy — status & openstaande acties

Issue: [#749](https://github.com/MinBZK/MijnOverheidZakelijk/issues/749) — per Pull
Request een deploy op ZAD.
Branch: `feature/749-zad-pr-deploy` (afgetakt van `feature/nmc-skeleton`).
ZAD-project: `nd-j7s`.

## Hoe ZAD hier werkt (kort)

De `RijksICTGilde/zad-actions/deploy` action zet alleen **welk container-image** +
optioneel **`clone-from`** (config erven van een bestaande deployment). De action
zet **geen** app-config (DB-url, wachtwoorden, `hash.pepper`, notify-keys). Die
config leeft in de deployment zelf, ingesteld in de ZAD **Operations Manager**.
Een PR-deploy erft die via `clone-from <base>`.

→ Gevolg: er moet **eenmalig** een base-deployment met de juiste env bestaan,
anders start de app zonder DB en gaat de deploy rood (`/q/health/ready` faalt).

## Klaar (code-kant)

- [x] Branch aangemaakt vanaf `feature/nmc-skeleton`
- [x] Container-image via **jib** (`quarkus-container-image-jib`) — geen
      Dockerfile/container-daemon; base `eclipse-temurin:25-jre` (Java 25), non-root
      (`quarkus.jib.user=1001`). Image-naam in `application.properties`, rest via
      CI-flags (`-Dquarkus.container-image.*`)
- [x] `pom.xml` — `quarkus-smallrye-health` toegevoegd (`/q/health/ready`;
      readiness bevat datasource-check → vangt DB-boot-faal af)
- [x] `.github/workflows/deploy.yml` — build → deploy → cleanup op `pull_request`,
      `project-id: nd-j7s` ingevuld
- [x] `docs/zad-deploy.md` — setup-gids
- [x] Repo-secret `ZAD_API_KEY` gezet
- [x] Lokale build geverifieerd (fast-jar gebouwd, health-extensie gebundeld)

## ZAD-kant (gereed)

- [x] Base-deployment **`feature`** met component **`nmcapi`** geconfigureerd in
  Operations Manager (env-vars: DB-url/creds, notify-keys, `hash.pepper`,
  flyway-flag). `deploy.yml` heeft `component: nmcapi` + `clone-from: feature`.

> Controleer dat de env op `feature/nmcapi` `QUARKUS_FLYWAY_MIGRATE_AT_START=true`
> bevat — anders faalt de eerste boot op een lege DB (`schema-management=validate`).

## Optioneel later

- [ ] `GITHUB_ADMIN_TOKEN` (repo-admin PAT) als repo-secret toevoegen, daarna in
  `deploy.yml` `delete-github-env` + `delete-github-deployments` weer op `'true'`
  zetten (nu `'false'` om hard falen van cleanup te voorkomen).

## Live (PR #6, in review)

- [x] Branch gepusht + PR [#6](https://github.com/MinBZK/moza-notificatiemanagementcomponent/pull/6)
  geopend (target `feature/nmc-skeleton`) — wacht op review, nog niet mergen.
- [x] Deploy end-to-end groen: build → image → ZAD-deploy → health.
- URLs: `https://nmcapi-pr-6-nd-j7s.rig.prd1.gn2.quattro.rijksapps.nl`
  → `/q/swagger-ui`, `/q/openapi`, `/q/health/ready`.

### Twee leerpunten onderweg (al opgelost in de workflow)
- **JDBC-URL** moet volledig zijn: `jdbc:postgresql://host:5432/<db>` (env op
  `feature/nmcapi`), niet enkel host of `jdbc://...`.
- **Image-digest i.p.v. mutable `pr-N` tag**: ZAD/k8s pullt een gelijke tag niet
  opnieuw, waardoor image-wijzigingen niet live kwamen. Deploy gaat nu op digest.
- **Swagger UI** alleen in preview-image via de Maven-flag
  `-Dquarkus.swagger-ui.always-include=true`; prod-build blijft schoon.

## Restpunten

- [ ] `GITHUB_ADMIN_TOKEN` toevoegen → dan `delete-github-env`/`-deployments` in
  `deploy.yml` weer op `'true'`.
