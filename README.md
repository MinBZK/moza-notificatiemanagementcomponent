# NotificatieManagementComponent (NMC)

## Geïmplementeerde functionaliteit

Deze implementatie ondersteunt de centraal-profiel happy-flow, inclusief de
asynchrone bezorgstatus:

1. Een Dienstverlener (rechtstreeks, of via een OMC) roept
   `POST /api/nmc/v1/centraal/notificaties` aan met een identificatie (BSN/KVK/RSIN),
   dienstverlener/dienst, berichttype, optionele berichtgegevens en optioneel een `callbackUrl`.
2. De NMC haalt synchroon de contactgegevens op bij de **Profielservice** op
   basis van die identificatie.
3. De NMC verstuurt synchroon een e-mail via **NotifyNL**
   (`POST /v2/notifications/email`) en verwacht hierop direct een `201`.
4. De NMC slaat de notificatie op (PostgreSQL) met status `sending` en de
   eventuele `callbackUrl`, en retourneert een `notificatieId` aan de aanroeper.
5. NotifyNL roept asynchroon `POST /api/nmc/v1/notifynl-callback` aan met de
   bezorgstatus (delivery receipt).
6. De NMC zoekt de notificatie op, werkt de status bij en stuurt — als er een
   `callbackUrl` is meegegeven — een statusupdate (**CloudEvents NL GOV**) naar
   die URL. Na een geslaagde callback wordt het record verwijderd (minimale
   dataminimalisatie).

De `callbackUrl` is optioneel: Dienstverleners zonder eigen webhook-endpoint kunnen
de status opvragen via `GET /centraal/notificaties/{id}` (nog niet geïmplementeerd).

Dit is het **centraal profiel**-scenario (zie "De twee assen" hieronder),
waarbij de NMC zelf de contactgegevens opzoekt.

### Decentraal profiel (doorgeefluik)

Naast het centraal profiel ondersteunt de NMC het **decentraal profiel**: de
aanroeper (doorgaans een OMC) heeft de contactgegevens zelf al bepaald en levert
het e-mailadres rechtstreeks aan via `POST /api/nmc/v1/decentraal/notificaties`
(e-mailadres, berichttype, optionele berichtgegevens en optioneel een `callbackUrl`).
De NMC slaat de Profielservice-lookup (stap 2 hierboven) over en verstuurt direct
via NotifyNL; stappen 3 t/m 6 (opslaan, `notificatieId` retourneren, asynchrone
bezorgstatus via de NotifyNL-callback en de CloudEvents-statusupdate naar de
`callbackUrl`) zijn identiek aan het centraal profiel.

Nog **niet** geïmplementeerd, maar wel onderdeel van de visie verderop in dit
document:

- Contactherstel (fysieke post via Printstraat/Postadres,
  KvK/BRP/NHR-fallback)
- Herverzending
- Contactherstel bij het decentraal profiel (op verzoek van de aanroeper)
- Een koppeling met de **Templating Service**: het `template_id` wordt voorlopig
  bepaald door een lokale `BerichtType`-enum in de NMC, niet via een externe Templating Service
- Een observability-koppelvlak
- `GET /centraal/notificaties/{id}`: statuspoll voor Dienstverleners zonder callbackUrl
- Bearer-JWT-authenticatie voor de uitgaande consument-callback (momenteel geen
  auth op de callback naar de Dienstverlener)

## Wat is de NMC?

De NMC is de centrale component voor het versturen van officiële
overheidsnotificaties namens een Dienstverlener. Een Procesapplicatie (of een
OMC namens een Dienstverlener) levert bij de NMC aan **wat** er verstuurd moet
worden (welke ontvanger, welk bericht, welke dienst); de NMC zorgt vervolgens
voor:

- het ophalen van de juiste berichttekst bij de **Templating Service**,
- het versturen van het bericht via **NotifyNL** (e-mail, en via Printstraat
  ook fysieke post),
- het verwerken van de bezorgstatus die NotifyNL asynchroon terugmeldt,
- en, als digitale bezorging mislukt, het orkestreren van **contactherstel**:
  alsnog een fysieke brief versturen, of een ander kanaal proberen.

De NMC bevindt zich tussen een aantal andere systemen:

- **Dienstverlener / Procesapplicatie**: de partij die wil dat er een
  notificatie verstuurd wordt
- **OMC (Output Management Component)**: een per-Dienstverlener component die
  vaak vooraf gaat aan de NMC, maar niet verplicht is
- **Profielservice**: centrale service die BSN/KVK/RSIN kan omzetten naar
  contactvoorkeuren en -gegevens, en contactherstelopties beheert
- **Templating Service**: levert berichttemplates op basis van een
  berichttype
- **NotifyNL**: de daadwerkelijke verzendkanaal voor e-mail en (via
  Printstraat) fysieke post
- **Printstraat / Postadres**: drukken en verzenden van fysieke post, via
  NotifyNL

## De twee assen

Het gedrag van de NMC wordt bepaald door twee onafhankelijke assen. Ze staan
los van elkaar, een keuze op de ene as zegt niets over de andere.

### As 1. Zit er een OMC voor de NMC?

- **Ja**: de OMC is de aanroeper van de NMC en ontvangt statusupdates terug,
  die het weer doorgeeft aan de Procesapplicatie. De OMC kan bij de aanvraag
  een optionele `callbackUrl` meegeven waarop de NMC asynchroon statusupdates
  terugstuurt (CloudEvents NL GOV).
- **Nee**: een dienst/voorziening zonder eigen OMC roept de NMC rechtstreeks
  aan.

### As 2. Profieltype: centraal of decentraal

- **Centraal profiel (NMC "in the lead"**): de aanroeper geeft alleen een
  identificatie mee (BSN/KVK/RSIN + dienstverlener + dienst + berichttype +
  berichtgegevens). De NMC:
  - haalt zelf de contactgegevens op bij de Profielservice,
  - regisseert bij een mislukte bezorging zelf het contactherstel (incl.
    eventueel terugvallen op KvK/BRP/NHR voor een adres),
  - ontzorgt de aanroeper dus volledig.
- **Decentraal profiel (NMC is een "doorgeefluik"**): de aanroeper heeft zelf
  al de contactgegevens bepaald (bijvoorbeeld via zijn eigen DCProfiel) en
  geeft deze compleet mee (e-mailadres of postadres, verzendkanaal, etc.). De
  NMC:
  - verstuurt het bericht zoals opgedragen,
  - meldt het resultaat terug aan de aanroeper,
  - contactherstel wordt door de aanroeper zelf geïnitieerd, de NMC voert dit
    enkel uit.

Een OMC voor de NMC betekent dus niet automatisch een decentraal profiel: een
OMC kan ook gewoon een kale identificatie doorgeven en het centraal profiel
laten gebruiken.

De NMC implementeert momenteel alleen het **centraal profiel**-scenario van As 2,
onafhankelijk van As 1 (een eventuele OMC geeft hierbij alleen een kale
identificatie door).

## Belangrijkste flow

1. **Notificatie aanmaken**: de aanroeper dient een aanvraag in. Bij een
   centraal profiel volstaat een identificatie; bij een decentraal profiel
   worden ook het verzendkanaal en de ontvangergegevens meegegeven.
2. **Versturen**: de NMC haalt (in een latere stap) de juiste template op en
   stuurt het bericht via NotifyNL. De notificatie wordt opgeslagen met status
   `sending`.
3. **Bezorgstatus verwerken**: NotifyNL meldt asynchroon terug of de
   bezorging is gelukt of mislukt. De NMC werkt de status bij en stuurt een
   statusupdate naar de `callbackUrl` van de aanroeper (indien opgegeven).
4. **Contactherstel (indien nodig)**: bij een mislukte bezorging kan een
   nieuwe verzendpoging via een ander kanaal worden gestart: bij een centraal
   profiel initieert de NMC dit zelf, bij een decentraal profiel gebeurt dit op
   verzoek van de aanroeper.

Stap 1, 2 en 3 zijn geïmplementeerd (alleen het centraal-profiel-pad,
zonder Templating Service). Stap 4 is nog niet gebouwd.

## Interne componenten (C4-componentmodel)

De NMC bestaat intern uit de volgende componenten (gebaseerd op het C4-componentdiagram).
Geïmplementeerde componenten zijn vetgedrukt; de rest is toekomstig ontwerp.

| Component | Type | Omschrijving |
|---|---|---|
| **Centrale-regie-API** | REST (controller) | Inbound endpoint voor het centraal profiel: intake op identificerend nummer; NMC resolvet zelf de contactgegevens via de Profielservice. |
| **Afleverstatus-callback** | REST (controller) | Webhook waarop NotifyNL delivery receipts meldt. |
| **Notificatie-orchestrator** | Service | Coördineert contactgegevens ophalen, opslag in de database, versturen en statusverwerking. |
| **Profielservice-adapter** | Client | Haalt contactvoorkeur op bij de Profielservice en kan een e-mailadres invalideren. |
| **Verzendadapter** | Client (bearer-JWT) | Verstuurt berichten via NotifyNL (`template_id` + `personalisation`). |
| **Consument-callback-adapter** | Webhook-client (CloudEvents NL GOV) | Stuurt de afleverstatus asynchroon terug naar de aanroeper via de opgegeven `callbackUrl`. |
| **notificatiedatabase** | PostgreSQL | Slaat referentie, status en (bij centraal profiel) het versleuteld identificerend nummer op; records worden verwijderd zodra de callback is verstuurd. |
| Decentrale-regie-API | REST (controller) | Inbound endpoint voor het decentraal profiel: intake met reeds opgehaalde contactgegevens. |
| Adres-adapter | Client | Haalt een postadres op bij KvK Handelsregister of BRP als fallback bij contactherstel. |
| Contactherstel-coordinator | Component | Coördineert de contactherselstroom bij onbereikbaarheid; initieert een nieuwe verzendpoging via een ander kanaal en meldt dit aan de Contactherstel-dienst. |

De externe systemen die de NMC aanroept of van ontvangt:

- **Dienstverlener / OMC** — initiëert notificaties (centraal of decentraal)
- **NotifyNL** — verzendt template-berichten en meldt afleverstatus terug
- **Profiel Service** — levert contactgegevens en -voorkeuren
- **Contactherstel** — voert de uiteindelijke contactherselactie uit (fysieke post via Printstraat)
- **KvK Handelsregister / BRP** — adresgegevens als fallback bij contactherstel

## Domeinmodel

De `Notificatie`-entiteit is minimaal geïmplementeerd: hij bevat een
NMC-interne `notificatieId` (UUID), de `notifyNlNotificatieId` (de referentie
die NotifyNL intern gebruikt voor correlatie met delivery receipts), de
optionele `callbackUrl` en de huidige `status`. Records worden verwijderd zodra
de callback succesvol is verstuurd (dataminimalisatie).

Onderstaande entiteiten zijn de **beoogde eindsituatie** voor latere stories en
nog niet geïmplementeerd:

- **`Verzending`**: één concrete verzendpoging (primaire verzending of
  contactherstelpoging). Houdt het verzendkanaal, de ontvangergegevens, de
  huidige status en de Notify-referentie bij.
- **`StatusGebeurtenis`**: een append-only logboek van statusupdates per
  verzending (bijv. delivery receipts van NotifyNL). Basis voor een toekomstig
  observability-koppelvlak.

## API

De huidige endpoints zitten onder `/api/nmc/v1`:

- **`POST /centraal/notificaties`**: haalt contactgegevens op bij de Profielservice,
  verstuurt de e-mail via NotifyNL, slaat de notificatie op en retourneert een
  `notificatieId`. Optioneel kan een `callbackUrl` worden meegegeven voor
  asynchrone statusupdates. Retourneert `200` op succes, `400` als er geen
  partij of e-mailadres gevonden wordt, `500` bij een Profielservice-fout, en
  `502` als NotifyNL geen `201` teruggeeft.
- **`POST /notifynl-callback`**: webhook waarop NotifyNL de bezorgstatus
  (delivery receipt) van een verzending terugmeldt. Beveiligd met een bearer
  token dat geconfigureerd wordt in NotifyNL's dashboard en via
  `notify.callback.bearer-token` in de NMC. De NMC werkt de status bij en
  stuurt — indien een `callbackUrl` aanwezig is — een **CloudEvents NL GOV**
  statusupdate naar die URL. Retourneert `204` op succes, `401` bij een
  ontbrekend of ongeldig bearer token, en `404` als de NotifyNL-referentie
  onbekend is. Dit endpoint heeft een eigen, losse OpenAPI-specificatie (zie
  hieronder), zodat het makkelijk te verwijderen is zodra de NMC publiek
  bereikbaar is en NotifyNL een echte callback-URL kan benaderen.

Gepland/toekomstig (nog niet aanwezig):

- **`GET /centraal/notificaties/{id}`**: status van een eerder verstuurde notificatie
  opvragen (alternatief voor de callbackUrl).
- **`POST /centraal/notificaties/{id}/contactherstel`**: een nieuwe verzendpoging
  (contactherstel) starten voor een bestaande notificatie.

De `/centraal/notificaties`-specificatie is beschikbaar via `/q/swagger-ui` wanneer de
applicatie draait (`./mvnw quarkus:dev`); zie de toelichting bij
"OpenAPI-specificatie & codegen" hieronder voor waarom `/notifynl-callback`
daar niet in staat.

## OpenAPI-specificatie & codegen

Het contract van `/api/nmc/v1` is **spec-first** en bestaat uit twee losse
specificaties:

- `src/main/resources/META-INF/openapi.yaml` — `POST /centraal/notificaties` (de
  centrale-regie-flow).
- `src/main/resources/META-INF/notifynl-callback-openapi.yaml` — `POST
  /notifynl-callback`, in een eigen bestand zodat het zelfstandig te verwijderen
  is zodra dit endpoint niet meer nodig is (zie de API-sectie hierboven).

- Quarkus serveert alleen `openapi.yaml` ongewijzigd via `/q/openapi` en
  `/q/swagger-ui` (SmallRye OpenAPI pikt automatisch een bestand met die naam
  op uit `META-INF`; `mp.openapi.scan.disable=true` staat aan, dus er wordt
  niet ook nog automatisch op annotaties gescand).
  `notifynl-callback-openapi.yaml` heet bewust anders en wordt dus **niet**
  via swagger-ui getoond — die is alleen input voor de codegen hieronder, niet
  voor runtime-documentatie.
- Bij elke build genereert de `openapi-generator-maven-plugin` (twee losse
  `<execution>`s, één per spec) hieruit de JAX-RS-interfaces
  (`nl.rijksoverheid.moz.nmc.api.NotificatiesApi`,
  `nl.rijksoverheid.moz.nmc.notifynlcallback.api.NotifyNlCallbackApi`) en de
  request/response-modellen (`nl.rijksoverheid.moz.nmc.api.model.*`,
  `nl.rijksoverheid.moz.nmc.notifynlcallback.api.model.*`) in
  `target/generated-sources/openapi` (niet ingecheckt).
  `CentraleNotificatieController` (package `controller`) en
  `NotifyNLCallbackController` (package `notifynlcallback.controller`)
  implementeren de gegenereerde interfaces.

Om een contract aan te passen: wijzig `META-INF/openapi.yaml` of
`META-INF/notifynl-callback-openapi.yaml` en draai een build (`./mvnw compile`,
`test` of `quarkus:dev`) — de gegenereerde interfaces/modellen worden
automatisch bijgewerkt (en, voor `openapi.yaml`, ook de swagger-ui).

## Lokaal draaien

De applicatie gebruikt een **PostgreSQL**-database, met het schema beheerd via
**Flyway**-migraties (`src/main/resources/db/migration`). Start een lokale
instantie met:

```shell
podman compose up -d
```

Dit start één Postgres-container met de `nmc`-database/-user (via de
standaard `POSTGRES_DB`/`POSTGRES_USER`/`POSTGRES_PASSWORD`-omgevingsvariabelen).
In test mode (`%test`) wordt H2 in-memory gebruikt. Voor productie moeten
`QUARKUS_DATASOURCE_USERNAME` en `QUARKUS_DATASOURCE_PASSWORD` als
omgevingsvariabelen worden meegegeven, en
draait de migratie niet automatisch bij opstarten
(`%prod.quarkus.flyway.migrate-at-start=false`) maar als los init-proces/job.

> Let op: dit `docker-compose.yml` bootstrapt alleen de database van dit
> component. Draai je lokaal ook Profielservice met zijn eigen
> `docker-compose.yml`, dan claimen beide standaard hostport 5432 — niet
> gelijktijdig op dezelfde poort starten.

De applicatie roept de Profielservice en NotifyNL aan via gegenereerde REST
clients (zie "OpenAPI-specificatie & codegen" hierboven), geconfigureerd in
`src/main/resources/application.properties`:

- `quarkus.rest-client.profielservice.url` — in `%dev` standaard
  `http://localhost:8080`; er moet dus een (lokale of gestubde) Profielservice
  op die poort draaien. In `%test` staat dit op `http://localhost:8081`, maar
  daar wordt de client toch gemockt.
- `quarkus.rest-client.notify.url` en `notify.api-key` — wijzen naar NotifyNL.
  `notify.api-key` staat leeg in de repository en moet lokaal (bijvoorbeeld in
  `application-dev.properties`, niet ingecheckt) ingevuld worden om de
  e-mailflow daadwerkelijk te laten werken.
- `notify.callback.bearer-token` — het bearer token waarmee NotifyNL zich
  authenticeert op het `/notifynl-callback`-endpoint. Staat leeg in de
  repository; zonder waarde start de applicatie niet op. Lokaal in te vullen
  via `application-dev.properties`. In productie/ZAD als secret instellen en
  hetzelfde token configureren in NotifyNL's dashboard onder "API integration
  → Callbacks".
- `hash.pepper` — geheime pepper voor de keyed HMAC-SHA-256 in `HashHelper`
  (gebruikt om BSN/KVK/RSIN te pseudonimiseren voor de logboek-context). Staat
  ook leeg in de repository; zonder waarde (en zonder `%dev`/`%test`-override)
  start de applicatie niet op. Lokaal in te vullen via
  `application-dev.properties`.

Start de applicatie in dev mode:

```shell
./mvnw quarkus:dev
```

Voor `./mvnw test` worden de Profielservice- en NotifyNL-clients en de
consument-callback-adapter gemockt; hiervoor is geen draaiende externe service
nodig.

Bovenstaande draait de app in **dev-mode** (`%dev`-profiel: Postgres uit
`podman compose`, Flyway migreert automatisch). Wil je in plaats daarvan de
**container-image** lokaal bouwen en draaien (prod-profiel, zoals op ZAD), zie
[`docs/lokaal-testen.md`](docs/lokaal-testen.md).

## Status & vervolgstappen

De NMC implementeert de centraal- en decentraal-profiel happy-flows inclusief de
asynchrone bezorgstatus en consument-callback, zoals beschreven onder
"Geïmplementeerde functionaliteit". Nog **niet** aanwezig:

- **Contactherstel** en **herverzending** (voor beide profielen)
- Een koppeling met de **Templating Service** (het `template_id` wordt voorlopig
  bepaald door een lokale `BerichtType`-enum, niet via een externe Templating Service)
- **`GET /centraal/notificaties/{id}`** voor statuspoll zonder callbackUrl
- **Bearer-JWT-authenticatie** voor de uitgaande consument-callback
- Een uitgewerkt **observability-koppelvlak**
