# AGENTS.md

Werkregels voor de NotificatieManagementComponent (NMC): wat je moet weten om
wijzigingen aan te brengen zonder iets stilzwijgend te breken.

- Overzicht, domeinmodel, API en lokaal draaien: [`README.md`](README.md).
- Begrippen, de twee assen en hoe de mechanismen werken: [`docs/architectuur.md`](docs/architectuur.md).
- Regels voor een deel van de code staan in `.claude/rules/`: migraties, contracten, tests en
  het statusmodel. Claude Code laadt ze vanzelf zodra je in die bestanden werkt; werk je met een
  ander hulpmiddel, lees ze dan zelf.

## Taal

Communicatie, commentaar en commitmessages in het Nederlands.

- **Domeinbegrippen blijven Nederlands**, ook als identifier: `Notificatie`,
  `Dienstverlener`, `BerichtType`, `verwerkAfleverstatus`, `WebhookUrlValidator`.
- **Vast technisch idioom blijft Engels**: adapter, filter, retry, callback, factory,
  repository. Vertalen maakt die termen minder herkenbaar, niet meer.
- **Namen van systemen blijven zoals ze heten**: Profielservice, NotifyNL, Printstraat.
- **Testnamen beschrijven het gedrag in het Nederlands**, als `methode_situatie_verwachting`:
  `lever_geen2xx_gooitLeveringExceptionNaEenAanroep`, `interneHostnaam_wordtGeweigerd`.

## De NMC in het kort

De NMC neemt een notificatieverzoek aan van een Dienstverlener, verstuurt het via NotifyNL,
verwerkt de afleverstatus en meldt de uitkomst terug via een eventfeed en een webhook. De
logica hangt af van **centrale of decentrale regie**: bij centrale regie levert de aanroeper
alleen een BSN/KVK/RSIN en zoekt de NMC het adres op bij de Profielservice; bij decentrale
regie levert de aanroeper het adres zelf. Of er een OMC vóór de NMC zit, staat daar los van.
Haal die twee niet door elkaar; zie `docs/architectuur.md`.

Voor de soorten berichten (notificatie, kennisgeving, gebeurtenisbericht, contactherstelmelding)
gelden de begrippen van MOZa: <https://mijnoverheidzakelijk.nl/onderwerpen/berichten/begrippen/index.md>
(markdown). Gebruik die namen in contractteksten en documentatie; hernoem bestaande code niet
zonder afstemming.

Het ontwerp (statusmodel, eventlog, takentabel) staat in
[ADR 0024](https://github.com/MinBZK/MijnOverheidZakelijk/blob/main/Docs/structurizr/decisions/0024-georkestreerde-state-machine-met-eventlog.md)
in de MijnOverheidZakelijk-repo.

## Harde regels

Elk van deze regels voorkomt een fout die de build niet altijd vangt.

1. **De status verandert alleen via `Overgangsfunctie`.** Die vergrendelt de rij, toetst de
   overgang en schrijft het event. Een deferred trigger weigert een nieuwe versie zonder event.
2. **Elke Hibernate-update van `notificatie` verhoogt `@Version` en vraagt dus een event.**
   Een schrijver zonder overgang (zoals het wissen van een sleutel) gaat met native SQL via
   `NotificatieRepository`, zonder versie.
3. **De versleutelde kolommen en `kek_versie` staan op `updatable = false`.** Anders schrijft
   een overgang op een eerder geladen entity een gewiste of geherwrapte sleutel terug.
4. **Gebruik de geïnjecteerde `Clock`**, nooit `now()` zonder klok. Tests zetten de klok vast.
5. **Queries staan in een repository.** Entiteiten krijgen geen finders en geen native SQL,
   `getEntityManager()` verlaat de repository niet.
6. **Afleverstatussen komen at-least-once en ongeordend binnen.** Elke verwerking moet een
   herhaling en een oudere status verdragen zonder iets te veranderen.
7. **Migraties zijn onveranderlijk.** Voeg een nieuwe toe; de volgende is **V17**. V11
   ontbreekt in de reeks; vul dat gat niet, want Flyway weigert een versie onder een al
   toegepaste.
8. **Schrijf geen DTO met de hand en bewerk niets onder `target/generated-sources`.** Pas het
   contract aan en bouw opnieuw.
9. **Zet geen JAX-RS-annotatie op een controllermethode.** Dan vervallen alle annotaties van
   de gegenereerde interface voor die methode, inclusief `@Valid`.
10. **Een test rekt zichtbaarheid hooguit op tot package-private**, met een comment met de
    reden. `public` maakt van een implementatiedetail een belofte; reflectie is geen alternatief.
11. **Een nieuwe statuswaarde of taaksoort vraagt een migratie.** `NotificatieStatus` en
    `PogingStatus` staan als `CHECK`-constraint; een `TaakSoort` heeft een eigen partitie.
12. **Elke codewijziging krijgt tests die falen als het gedrag verandert**: happy én unhappy
    paths, en testdata die het gedrag uitlokt (leeg, één en meerdere; beide kanten van een grens).

## Build en test

Java en Maven komen uit SDKMAN en staan niet op `PATH`:

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
podman compose up -d                    # PostgreSQL voor dev-mode (docker compose werkt ook)
./mvnw quarkus:dev                      # live reload op http://localhost:8080
./mvnw verify                           # volledige suite + coverage-gate
./mvnw test -Dtest=NaamVanDeTest        # één testklasse, zonder coverage-gate
```

- **Tests hebben geen containers nodig**: ze draaien op een embedded PostgreSQL 18 (Zonky).
  Elke `@QuarkusTest` deelt één database, dus een test ruimt zijn eigen rijen op. Alle tests
  draaien onder Surefire; Failsafe wordt overgeslagen (`skipITs=true`).
- **De coverage-gate hangt aan `verify`, niet aan `package` of `test`.** Draai lokaal
  `./mvnw verify` voor je een PR opent. CI-job `Maven verify` is een verplichte check op `main`;
  hernoem hem niet zonder de branch protection mee aan te passen.
- **JaCoCo-drempels en excludes staan in `pom.xml`.** De excludes staan ook als
  `%test.quarkus.jacoco.excludes` in `application.properties`; `ConfiguratiePariteitTest`
  bewaakt dat ze gelijk blijven.
- **`quarkus.jacoco.*` alleen onder `%test`**: de extensie is test-scoped en een onbekende
  sleutel laat het prod-image niet starten.
- **Dev-mode en de Profielservice** claimen allebei poort 8080
  (`%dev.quarkus.rest-client.profielservice.url`). Draai je een echte Profielservice lokaal,
  geef er dan één een andere poort. De compose van `../moza-profiel-service` claimt ook 5432.
- Container-image lokaal bouwen: `docs/lokaal-testen.md`. Image via jib; de Dockerfiles in
  `src/main/docker` worden niet gebruikt.

## Configuratie en secrets

Deze waarden staan leeg in `application.properties` en zijn **verplicht**: zonder `nmc.kek.*`
start de applicatie niet, de andere falen pas bij het eerste gebruik. Lokaal horen ze in
een niet-ingecheckte `src/main/resources/application-dev.properties`; onder `%test` staan
dummywaarden.

| Property | Gebruikt door |
|----------|---------------|
| `notify.api-key` | `NotifyNLVerzendAdapter`: JWT voor NotifyNL |
| `notify.callback.bearer-token` | `NotifyNLCallbackAuthFilter`: controle op de NotifyNL-callback |
| `hash.pepper` | `HashHelper`: HMAC-SHA-256 om BSN/KVK/RSIN en e-mailadressen te pseudonimiseren voor het logboek |
| `nmc.kek.huidige-versie` | `ConfigKekProvider`: KEK-versie waarmee `Sleutelbeheer` nieuwe sleutels wrapt |
| `nmc.kek.versie.<n>` | `ConfigKekProvider`: KEK per versie, base64 van 32 bytes; oudere versies zolang er rijen mee bestaan |
| `nmc.webhook.jwt.private-key` | `WebhookSleutel`: RSA-sleutel (PKCS#8-PEM, minstens 2048 bits) voor de webhook-JWT |
| `nmc.webhook.jwt.key-id` | `WebhookSleutel`: `kid` in de JWT-header en de JWKS |

Ook `quarkus.rest-client.profielservice.url` en `quarkus.rest-client.notify.url` hebben buiten
`%test` geen default. Prod-config loopt via env-vars: zie `docs/zad-deploy.md`. Waarden met een
default staan in `docs/architectuur.md`.

Jackson is in `pom.xml` bewust boven de Quarkus-BOM getild vanwege twee databind-advisories.
Haal die override pas weg als de Quarkus-BOM zelf een veilige versie levert.

## Codestijl

- **Packages zijn technisch ingedeeld**: `controller/`, `service/`, `domain/`, `repository/`,
  `client/<systeem>/`, `job/`, `helper/`, `validation/`. Uitzondering: `notifynlcallback/` heeft
  een eigen `controller/` en `filter/`, omdat het uit een eigen contract komt.
- **Constructor-injectie, geen field-injectie.** Dit wijkt bewust af van andere repo's.
- **Repositories implementeren `PanacheRepositoryBase<T, Id>`**, niet `PanacheRepository<T>`:
  die legt het id-type vast op `Long`, en methodes als `deleteById` gaan dan stil verkeerd.
- **Foutafhandeling**: adapters vertalen een `WebApplicationException` naar een eigen exception,
  services gooien domeinexceptions, en alleen controllers en filters bouwen een HTTP-antwoord via
  `Problems`. Er is geen globale exception mapper: wat een controller niet afvangt, wordt een 500.
- **Logboek**: een controllermethode met `@Logboek` zet `logboekContext.setDataSubjectId(...)`
  met een waarde uit `HashHelper`, nooit het ruwe BSN/KVK/RSIN of e-mailadres.
- **SQL-keywords in hoofdletters**, in native SQL, HQL, Panache-fragmenten, migraties en tests:
  `SELECT ... FROM Taak WHERE ... IS NULL`, ook `COUNT`. In HQL blijft `excluded` klein, want
  Hibernate leest het als alias. Namen van tabellen, kolommen, entiteiten en PostgreSQL-functies
  houden hun eigen schrijfwijze.
- **Witregels rond `if`-blokken met accolades**: een lege regel ervóór (behalve als het het
  eerste statement is), een erna, en een vóór een afsluitende `return`. Een eenregelige guard
  clause staat juist tegen de regel erboven. Pas het toe op code die je schrijft of wijzigt.
- **Webhook-URL's** gaan vóór elke levering door `WebhookUrlValidator.controleer`. Die is een
  denylist op vorm, geen volledige SSRF-bescherming.

## Commentaar en documentatie

- **Compact**: één of twee zinnen die zeggen wát er niet vanzelf spreekt en waaróm. De afweging
  en de alternatieven horen in de commitmessage of het issue.
- **Schrijf alleen op wat je hebt gecontroleerd.** Een verkeerde verklaring van een mechanisme
  is schadelijker dan geen commentaar. Weet je het niet zeker, laat het weg of noteer het als
  aanname.
- **Geen issuenummers in javadoc of commentaar.** Beschrijf het probleem zelf. Een kaal `#732`
  wijst in deze repo naar een PR, niet naar het issue. Herschrijf bestaande `TODO #nnn` als je
  die code toch aanraakt.
- **Verwijs in commentaar niet naar dit bestand**; beschrijf de regel zelf.
- **Houd documentatie bij in dezelfde PR.** Verander je het gedrag van een klasse, werk dan de
  javadoc bij en, als het mechanisme daar beschreven staat, `docs/architectuur.md`. Verandert een
  regel hier of in `.claude/rules/`, pas die dan ook aan.

## Git-werkwijze

- **Nooit direct naar `main`.** Alles via een branch en een PR, gemerged met squash.
- **Commitmessages**: korte Nederlandse beschrijving, eventueel met een conventional-commit-prefix
  (`fix:`, `test:`, `ci:`, `docs:`).
- **Gebruik de PR-template** in `.github/PULL_REQUEST_TEMPLATE.md` en voeg **geen** reviewer toe.
- **Issues staan in [MinBZK/MijnOverheidZakelijk](https://github.com/MinBZK/MijnOverheidZakelijk/issues)**,
  niet in deze repo, met `NMC:` vooraan in de titel. Elke PR hoort bij een issue:
  - het issuenummer vooraan in de branchnaam: `1049-callback-url-verbindingsmoment`;
  - het issue in de PR-beschrijving én de PR in het issue;
  - cross-repo altijd voluit: `MinBZK/MijnOverheidZakelijk#1049`, nooit kaal `#1049`.

## Review

- **`/nmc-review <PR-nummer>`** reviewt een PR met de projectchecklist, deelt de bevindingen in
  (fixen, issue, naar BA/PO, overslaan) en levert de fixes als PR bovenop die PR.
- **De agent `nmc-reviewer`** doet de review zelf, in een schone context. Gebruik hem ook vóór
  je eigen PR.
- **Hooks in `.claude/settings.json`** blokkeren een wijziging aan een bestaande migratie of aan
  gegenereerde code, waarschuwen bij `now()` zonder klok of een JAX-RS-annotatie op een
  controller, en draaien `./mvnw verify` vóór een `git push`. Ze zien alleen de Edit- en
  Write-tools; een wijziging via de shell (`sed -i`) gaat erlangs.

## Referentierepo's (naast deze repo)

- `../moza-profiel-service`: Profielservice, Quarkus (`src/main/resources/META-INF/openapi.yaml`)
- `../moza-verificatie-service`: Quarkus; voorbeeld van de NotifyNL-integratie
