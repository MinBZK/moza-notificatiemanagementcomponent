# NotificatieManagementComponent (NMC)

## Wat doet dit skeleton wel/niet

Deze implementatie ondersteunt op dit moment één synchrone happy-flow:

1. Een Dienstverlener (rechtstreeks, of via een OMC) roept
   `POST /api/nmc/v1/notificaties` aan met een identificatie (BSN/KVK/RSIN),
   dienstverlener/dienst en het te versturen bericht.
2. De NMC haalt synchroon de contactgegevens op bij de **Profielservice** op
   basis van die identificatie.
3. De NMC verstuurt synchroon een e-mail via **NotifyNL**
   (`POST /v2/notifications/email`) en verwacht hierop direct een `200`.
4. De NMC retourneert de Notify-referentie aan de aanroeper.

Dit is het **centraal profiel**-scenario (zie "De twee assen" hieronder),
waarbij de NMC zelf de contactgegevens opzoekt. Er wordt bewust **niets**
gepersisteerd: er is geen database, en contactgegevens die bij de
Profielservice worden opgehaald (met name adresgegevens) worden niet
opgeslagen door de NMC.

Nog **niet** geïmplementeerd, maar wel onderdeel van de visie verderop in dit
document:

- Contactherstel (fysieke post via Printstraat/Postadres,
  KvK/BRP/NHR-fallback)
- Herverzending
- Verwerken van asynchrone bezorgstatus (delivery receipts) van NotifyNL
- Het decentraal profiel / doorgeefluik-scenario voor OMC's
- Een koppeling met de Templating Service: het Notify-`template_id` komt
  voorlopig uit een vaste configuratiewaarde (`notify.template-id`)
- Een observability-koppelvlak

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
  die het weer doorgeeft aan de Procesapplicatie.
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
   stuurt het bericht via NotifyNL.
3. **Bezorgstatus verwerken**: NotifyNL meldt asynchroon terug of de
   bezorging is gelukt of mislukt. De NMC legt deze statusupdates vast.
4. **Contactherstel (indien nodig)**: bij een mislukte bezorging kan een
   nieuwe verzendpoging via een ander kanaal worden gestart: bij een centraal
   profiel initieert de NMC dit zelf, bij een decentraal profiel gebeurt dit op
   verzoek van de aanroeper.

Van deze flow implementeert dit skeleton alleen stap 1 (alleen het
centraal-profiel-pad) en stap 2 (zonder Templating Service). Stap 3 en 4 zijn
nog niet gebouwd.

## Domeinmodel

Onderstaand domeinmodel is de beoogde eindsituatie. De huidige code kent **geen
persistente entiteiten** — er is geen database. Een aanvraag wordt verwerkt via
request/response-DTO's (`NotificatieAanvraagRequest` /
`NotificatieResponse`) zonder dat er iets wordt opgeslagen.

- **`Notificatie`**: de aanvraag/het "geval" als geheel: voor wie (BSN/KVK/RSIN),
  namens welke dienstverlener/dienst, welk berichttype en welke berichtgegevens.
  Een notificatie heeft één of meer verzendingen.
- **`Verzending`**: één concrete verzendpoging: de oorspronkelijke (primaire)
  verzending, of een contactherstelpoging. Houdt het verzendkanaal, de
  ontvangergegevens, de huidige status en de Notify-referentie bij.
- **`StatusGebeurtenis`**: een append-only logboek van statusupdates per
  verzending (bijvoorbeeld de delivery receipts van NotifyNL). Vormt de basis
  voor een toekomstig observability-koppelvlak.

De huidige status van een notificatie wordt afgeleid van de meest recente
verzending. Er is bewust geen apart statusveld op `Notificatie` zelf, om te
voorkomen dat dit uit de pas gaat lopen.

## API

Het huidige endpoint zit onder `/api/nmc/v1`:

- **`POST /notificaties`**: voert de volledige synchrone happy-flow uit (zie
  "Wat doet dit skeleton wel/niet"): haalt contactgegevens op bij de
  Profielservice, verstuurt de e-mail via NotifyNL, en retourneert de
  Notify-referentie. Retourneert `200` op succes, `404` als er geen partij of
  e-mailadres gevonden wordt, en `502` als NotifyNL geen `200` teruggeeft.

Gepland/toekomstig (nog niet aanwezig):

- **`GET /notificaties/{id}`**: status van een eerder verstuurde notificatie
  opvragen, inclusief de status van alle verzendingen.
- **`POST /notificaties/{id}/contactherstel`**: een nieuwe verzendpoging
  (contactherstel) starten voor een bestaande notificatie.
- **`POST /notify-callback`**: webhook waarop NotifyNL de bezorgstatus
  (delivery receipt) van een verzending terugmeldt.

De volledige OpenAPI-specificatie is beschikbaar via `/q/swagger-ui` wanneer de
applicatie draait (`./mvnw quarkus:dev`).

## OpenAPI-specificatie & codegen

Het contract van `/api/nmc/v1/notificaties` is **spec-first**:
`src/main/resources/META-INF/openapi.yaml` is de bron van waarheid.

- Quarkus serveert dit bestand ongewijzigd via `/q/openapi` en
  `/q/swagger-ui` (`mp.openapi.scan.disable=true` staat aan, dus er wordt niet
  ook nog automatisch op annotaties gescand).
- Bij elke build genereert de `openapi-generator-maven-plugin` hieruit de
  JAX-RS-interface (`nl.rijksoverheid.moz.api.NotificatiesApi`) en de
  request/response-modellen (`nl.rijksoverheid.moz.api.model.*`) in
  `target/generated-sources/openapi` (niet ingecheckt).
  `NotificatieController` implementeert deze gegenereerde interface.

Om het contract aan te passen: wijzig `META-INF/openapi.yaml` en draai een
build (`./mvnw compile`, `test` of `quarkus:dev`) — de gegenereerde
interface/modellen en de swagger-ui worden automatisch bijgewerkt.

## Lokaal draaien

Er is geen database nodig. De applicatie roept de Profielservice en NotifyNL
aan via REST clients, geconfigureerd in
`src/main/resources/application.properties`:

- `quarkus.rest-client.profiel-service.url` — in `%dev`/`%test` standaard
  `http://localhost:8081`; er moet dus een (lokale of gestubde)
  Profielservice op die poort draaien.
- `quarkus.rest-client.notify.url`, `notify.api-key` en `notify.template-id` —
  wijzen naar NotifyNL. `notify.api-key` en `notify.template-id` staan leeg in
  de repository en moeten lokaal (bijvoorbeeld in
  `application-dev.properties`, niet ingecheckt) ingevuld worden om de
  e-mailflow daadwerkelijk te laten werken.

Start de applicatie in dev mode:

```shell
./mvnw quarkus:dev
```

Voor `mvn test`/`./mvnw test` worden de Profielservice- en NotifyNL-clients
gemockt; hiervoor is geen draaiende Profielservice of NotifyNL nodig.

## Status & vervolgstappen

Dit skeleton implementeert de synchrone centraal-profiel happy-flow zoals
beschreven onder "Wat doet dit skeleton wel/niet". Nog **niet** aanwezig:

- **Contactherstel** en **herverzending**
- Verwerking van **asynchrone bezorgstatus** (delivery receipts) van NotifyNL
- Het **decentraal profiel**-scenario en de bijbehorende OMC-statusmeldingen
- Een koppeling met de **Templating Service** (template-keuze op basis van
  `berichtType`)
- Een uitgewerkt **observability-koppelvlak**
