# NotificatieManagementComponent (NMC)

## Wat doet dit skeleton wel/niet

Deze implementatie ondersteunt de centraal-profiel happy-flow, inclusief de
asynchrone bezorgstatus:

1. Een Dienstverlener (rechtstreeks, of via een OMC) roept
   `POST /api/nmc/v1/notificaties` aan met een identificatie (BSN/KVK/RSIN),
   dienstverlener/dienst, het te versturen bericht en optioneel een `callbackUrl`.
2. De NMC haalt synchroon de contactgegevens op bij de **Profielservice** op
   basis van die identificatie.
3. De NMC verstuurt synchroon een e-mail via **NotifyNL**
   (`POST /v2/notifications/email`) en verwacht hierop direct een `201`.
4. De NMC slaat de notificatie op (PostgreSQL) met status `sending` en de
   eventuele `callbackUrl`, en retourneert een `notificatieId` aan de aanroeper.
5. NotifyNL roept asynchroon `POST /api/nmc/v1/notify-callback` aan met de
   bezorgstatus (delivery receipt).
6. De NMC zoekt de notificatie op, werkt de status bij en stuurt — als er een
   `callbackUrl` is meegegeven — een statusupdate (**CloudEvents NL GOV**) naar
   die URL. Na een geslaagde callback wordt het record verwijderd (minimale
   dataminimalisatie).

De `callbackUrl` is optioneel: Dienstverleners zonder eigen webhook-endpoint kunnen
de status opvragen via `GET /notificaties/{id}` (nog niet geïmplementeerd).

Dit is het **centraal profiel**-scenario (zie "De twee assen" hieronder),
waarbij de NMC zelf de contactgegevens opzoekt.

Nog **niet** geïmplementeerd, maar wel onderdeel van de visie verderop in dit
document:

- Contactherstel (fysieke post via Printstraat/Postadres,
  KvK/BRP/NHR-fallback)
- Herverzending
- Het decentraal profiel / doorgeefluik-scenario voor OMC's
- Een koppeling met de Templating Service: het Notify-`template_id` komt
  voorlopig uit een vaste configuratiewaarde (`notify.template-id`)
- Een observability-koppelvlak
- `GET /notificaties/{id}`: statuspoll voor Dienstverleners zonder callbackUrl
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

Dit skeleton implementeert alleen het **centraal profiel**-scenario van As 2,
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

Dit skeleton implementeert stap 1, 2 en 3 (alleen het centraal-profiel-pad,
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

- **`POST /notificaties`**: haalt contactgegevens op bij de Profielservice,
  verstuurt de e-mail via NotifyNL, slaat de notificatie op en retourneert een
  `notificatieId`. Optioneel kan een `callbackUrl` worden meegegeven voor
  asynchrone statusupdates. Retourneert `200` op succes, `404` als er geen
  partij of e-mailadres gevonden wordt, en `502` als NotifyNL geen `201`
  teruggeeft.
- **`POST /notify-callback`**: webhook waarop NotifyNL de bezorgstatus
  (delivery receipt) van een verzending terugmeldt. De NMC werkt de status bij
  en stuurt — indien een `callbackUrl` aanwezig is — een **CloudEvents NL GOV**
  statusupdate naar die URL. Retourneert `204` op succes en `404` als de
  NotifyNL-referentie onbekend is.

Gepland/toekomstig (nog niet aanwezig):

- **`GET /notificaties/{id}`**: status van een eerder verstuurde notificatie
  opvragen (alternatief voor de callbackUrl).
- **`POST /notificaties/{id}/contactherstel`**: een nieuwe verzendpoging
  (contactherstel) starten voor een bestaande notificatie.

De volledige OpenAPI-specificatie is beschikbaar via `/q/swagger-ui` wanneer de
applicatie draait (`./mvnw quarkus:dev`).

## OpenAPI-specificatie & codegen

Het contract van `/api/nmc/v1` is **spec-first**:
`src/main/resources/META-INF/openapi.yaml` is de bron van waarheid.

- Quarkus serveert dit bestand ongewijzigd via `/q/openapi` en
  `/q/swagger-ui` (`mp.openapi.scan.disable=true` staat aan, dus er wordt niet
  ook nog automatisch op annotaties gescand).
- Bij elke build genereert de `openapi-generator-maven-plugin` hieruit de
  JAX-RS-interfaces (`nl.rijksoverheid.moz.api.NotificatiesApi`,
  `nl.rijksoverheid.moz.api.NotifyCallbackApi`) en de request/response-modellen
  (`nl.rijksoverheid.moz.api.model.*`) in `target/generated-sources/openapi`
  (niet ingecheckt). `NotificatieController` en `NotifyCallbackController`
  implementeren de gegenereerde interfaces.

Om het contract aan te passen: wijzig `META-INF/openapi.yaml` en draai een
build (`./mvnw compile`, `test` of `quarkus:dev`) — de gegenereerde
interfaces/modellen en de swagger-ui worden automatisch bijgewerkt.

## Lokaal draaien

De applicatie gebruikt een **PostgreSQL**-database, met het schema beheerd via
**Flyway**-migraties (`src/main/resources/db/migration`). Start een lokale
instantie met:

```shell
docker-compose up -d
```

Dit start één Postgres-container met de `nmc`-database/-user (via de
standaard `POSTGRES_DB`/`POSTGRES_USER`/`POSTGRES_PASSWORD`-omgevingsvariabelen).
In test mode (`%test`) wordt H2 in-memory gebruikt. Voor productie moeten
`DB_USERNAME` en `DB_PASSWORD` als omgevingsvariabelen worden meegegeven, en
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
- `quarkus.rest-client.notify.url`, `notify.api-key` en `notify.template-id` —
  wijzen naar NotifyNL. `notify.api-key` en `notify.template-id` staan leeg in
  de repository en moeten lokaal (bijvoorbeeld in
  `application-dev.properties`, niet ingecheckt) ingevuld worden om de
  e-mailflow daadwerkelijk te laten werken.

Start de applicatie in dev mode:

```shell
./mvnw quarkus:dev
```

Voor `./mvnw test` worden de Profielservice- en NotifyNL-clients en de
consument-callback-adapter gemockt; hiervoor is geen draaiende externe service
nodig.

## Status & vervolgstappen

Dit skeleton implementeert de centraal-profiel happy-flow inclusief de
asynchrone bezorgstatus en consument-callback, zoals beschreven onder "Wat doet
dit skeleton wel/niet". Nog **niet** aanwezig:

- **Contactherstel** en **herverzending**
- Het **decentraal profiel**-scenario
- Een koppeling met de **Templating Service** (template-keuze op basis van
  `berichtType`)
- **`GET /notificaties/{id}`** voor statuspoll zonder callbackUrl
- **Bearer-JWT-authenticatie** voor de uitgaande consument-callback
- Een uitgewerkt **observability-koppelvlak**
