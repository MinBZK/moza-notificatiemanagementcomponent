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
- [x] `Dockerfile` — multi-stage Quarkus fast-jar build (Java 25)
- [x] `.dockerignore`
- [x] `pom.xml` — `quarkus-smallrye-health` toegevoegd (`/q/health/ready`;
      readiness bevat datasource-check → vangt DB-boot-faal af)
- [x] `.github/workflows/deploy.yml` — build → deploy → cleanup op `pull_request`,
      `project-id: nd-j7s` ingevuld
- [x] `.github/workflows/README-zad-deploy.md` — setup-gids
- [x] Repo-secret `ZAD_API_KEY` gezet
- [x] Lokale build geverifieerd (fast-jar gebouwd, health-extensie gebundeld)

## Openstaand (ZAD-kant — mens met Operations-Manager-toegang)

Eenmalig in Operations Manager, project `nd-j7s`:

1. [ ] Zorg dat er een **Postgres** beschikbaar is (managed addon óf los component).
2. [ ] Maak een **base-deployment** (bv naam `base`) met deze env-vars:
   - `DB_USERNAME`, `DB_PASSWORD`
   - `QUARKUS_DATASOURCE_JDBC_URL` (JDBC-url naar die Postgres)
   - `QUARKUS_FLYWAY_MIGRATE_AT_START=true` (container draait prod-profiel waar dit
     `false` staat; zonder migraties faalt boot op `schema-management=validate`)
   - `NOTIFY_API_KEY`, `NOTIFY_TEMPLATE_ID`
   - `HASH_PEPPER` (mag niet leeg in prod)
3. [ ] Bevestig de base-naam; staat die niet op `base`, pas `clone-from:` in
   `deploy.yml` aan.

> Wie project `nd-j7s` heeft aangemaakt / het ZAD-platformteam weet hoe stap 1-2 gaan.

## Optioneel later

- [ ] `GITHUB_ADMIN_TOKEN` (repo-admin PAT) als repo-secret toevoegen, daarna in
  `deploy.yml` `delete-github-env` + `delete-github-deployments` weer op `'true'`
  zetten (nu `'false'` om hard falen van cleanup te voorkomen).

## Security — actie vereist

- ⚠️ De `ZAD_API_KEY` is in platte tekst in een chat gedeeld → als blootgesteld
  beschouwen. Rotatie kan **niet** via de ZAD-GUI. **Met platformteam overleggen**
  om de key platform-zijdig in te trekken/vervangen; daarna het repo-secret
  `ZAD_API_KEY` bijwerken.

## Nog te doen voor activatie

- [ ] Branch pushen + PR openen (deploy gaat groen zodra base-deployment bestaat).
  PR met `pull_request`-trigger draait de workflow van de PR-head, dus de PR die
  deze workflow toevoegt deployt zichzelf.
