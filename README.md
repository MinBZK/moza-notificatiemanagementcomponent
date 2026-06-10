# NotificatieManagementComponent (NMC)

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

## Domeinmodel

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

Alle endpoints zitten onder `/api/nmc/v1`:

- **`POST /notificaties`**: maakt een nieuwe notificatie aan en start de
  eerste verzending. Retourneert `201` met de aangemaakte notificatie.
- **`GET /notificaties/{id}`**: haalt een notificatie op, inclusief de status
  van alle verzendingen. Retourneert `404` als de notificatie niet bestaat.
- **`POST /notificaties/{id}/contactherstel`**: start een nieuwe
  verzendpoging (contactherstel) voor een bestaande notificatie. Retourneert
  `201` met de nieuwe verzending.
- **`POST /notify-callback`**: webhook waarop NotifyNL de bezorgstatus
  (delivery receipt) van een verzending terugmeldt. Werkt de status van de
  bijbehorende verzending bij.

De volledige OpenAPI-specificatie is beschikbaar via `/q/swagger-ui` wanneer de
applicatie draait (`./mvnw quarkus:dev`).

## Lokaal draaien

Voor `dev`- en `prod`-mode is een PostgreSQL-database nodig (zie
`src/main/resources/application.properties`). Start deze lokaal via Docker
Compose:

```shell
docker-compose up -d
```

Start daarna de applicatie in dev mode:

```shell
./mvnw quarkus:dev
```

Voor `mvn test`/`./mvnw test` is geen database nodig: de tests draaien tegen
een in-memory H2-database.

Database weer afsluiten:

```shell
docker-compose down
```

Of, om ook de data in de `postgres_data`-volume te wissen:

```shell
docker-compose down -v
```

## Status & vervolgstappen

Dit is een eerste skeleton met het domeinmodel en de inbound API-contracten
van de NMC. Nog **niet** aanwezig:

- **Uitgaande integraties** met Profielservice, NotifyNL, Templating Service en
  OMC-statusmeldingen
- **Orkestratielogica** die de fasen aan elkaar knoopt (template ophalen,
  versturen via Notify, beslissen wanneer contactherstel nodig is)
- **Herverzending vs. contactherstel**-beslislogica
- Een uitgewerkt **observability-koppelvlak** (het datamodel ondersteunt dit
  al via `StatusGebeurtenis`, maar er is nog geen endpoint voor)
