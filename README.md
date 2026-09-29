# NotificatieManagementComponent (NMC)

## Geïmplementeerde functionaliteit

Deze implementatie ondersteunt de centraal-profiel happy-flow, inclusief de
asynchrone bezorgstatus:

1. Een Dienstverlener (rechtstreeks, of via een OMC) roept
   `POST /api/nmc/v1/centraal/notificaties` aan met een identificatie (BSN/KVK/RSIN),
   dienstverlener/dienst, berichttype en optionele berichtgegevens.
2. De NMC slaat de notificatie (status `aangenomen`, versleutelde ontvanger en
   berichtgegevens) en een verzendtaak op in één transactie en antwoordt `202` met
   het `notificatieId` en een `Location`.
3. Een worker claimt de verzendtaak, haalt de contactgegevens op bij de
   **Profielservice** en verstuurt de e-mail via **NotifyNL**
   (`POST /v2/notifications/email`) met het id van de verzendpoging als
   `reference`. De notificatie gaat via `in-verzending` naar `verzonden`.
4. Is er geen partij of geen e-mailadres, of weigert NotifyNL het adres, dan eindigt
   de notificatie in `niet-bezorgbaar`. Een storing bij de Profielservice of NotifyNL
   stelt de taak uit.
5. NotifyNL roept asynchroon `POST /api/nmc/v1/notifynl-callback` aan met de
   bezorgstatus (delivery receipt).
6. De NMC legt de uitkomst op de verzendpoging vast en voert de bijbehorende
   statusovergang uit; elke overgang is een event in het eventlog. De Dienstverlener
   leest die events als **CloudEvents NL GOV** uit de eventfeed, en heeft hij in het
   register een webhook, dan levert een terugkoppeltaak dezelfde events daarheen. Het
   slagen van die levering heeft geen invloed op wat de NMC vastlegt. Een retentiejob
   verwijdert de notificatie pas nadat `notificatie.retentie.bewaartermijn` (zie
   `application.properties`) verstreken is sinds de laatste statusovergang.

Dit is het **centraal profiel**-scenario (zie "De twee assen" hieronder),
waarbij de NMC zelf de contactgegevens opzoekt.

### Decentraal profiel

Naast het centraal profiel ondersteunt de NMC het **decentraal profiel**: de
aanroeper (doorgaans een OMC) heeft de contactgegevens zelf al bepaald en levert
het e-mailadres rechtstreeks aan via `POST /api/nmc/v1/decentraal/notificaties`
(e-mailadres, berichttype en optionele berichtgegevens). De NMC slaat de
Profielservice-lookup over; de rest (opslaan, `notificatieId` retourneren, asynchrone
bezorgstatus via de NotifyNL-callback, feed en webhook) is identiek aan het centraal
profiel.

Nog **niet** geïmplementeerd, maar wel onderdeel van de visie verderop in dit
document:

- Contactherstel (fysieke post via Printstraat/Postadres, KvK/BRP/NHR-fallback)
  en herverzending
- Een koppeling met de **Templating Service**: het `template_id` wordt voorlopig
  bepaald door een lokale `BerichtType`-enum in de NMC, niet via een externe Templating Service
- Een observability-koppelvlak
- `GET /centraal/notificaties/{id}`: status van één notificatie opvragen
- Registratie van een webhook via een API; nu zet beheer `webhook_url` in het register

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
  die het weer doorgeeft aan de Procesapplicatie. De OMC leest ze uit de eventfeed
  of ontvangt ze op de webhook die bij de onboarding in het register is gezet
  (CloudEvents NL GOV).
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
  - meldt het resultaat terug aan de aanroeper, die bij een mislukte bezorging
    zelf het vervolg bepaalt; contactherstel doet de NMC hier niet.

Een OMC voor de NMC betekent dus niet automatisch een decentraal profiel: een
OMC kan ook gewoon een kale identificatie doorgeven en het centraal profiel
laten gebruiken.

De NMC implementeert momenteel het digitale verzendpad van beide profielen van
As 2 (**centraal** én **decentraal**), onafhankelijk van As 1. Het
contactherstel-deel van beide profielen is nog niet gebouwd.

## Belangrijkste flow

1. **Notificatie aanmaken**: de aanroeper dient een aanvraag in. Bij een
   centraal profiel volstaat een identificatie; bij een decentraal profiel
   worden ook het verzendkanaal en de ontvangergegevens meegegeven.
2. **Versturen**: de NMC haalt (in een latere stap) de juiste template op en
   stuurt het bericht via NotifyNL. De notificatie wordt opgeslagen met status
   `sending`.
3. **Bezorgstatus verwerken**: NotifyNL meldt asynchroon terug of de
   bezorging is gelukt of mislukt. De NMC werkt de status bij; de overgang staat in de
   eventfeed en gaat naar de webhook van de Dienstverlener, als die er een heeft.
4. **Contactherstel (indien nodig)**: bij een mislukte bezorging in het centraal
   profiel start de NMC zelf een nieuwe verzendpoging via een ander kanaal. Bij
   het decentraal profiel handelt de aanroeper een mislukte bezorging zelf af.

Stap 1, 2 en 3 zijn geïmplementeerd (centraal- én decentraal-profiel, zonder
Templating Service). Stap 4 (contactherstel) is nog niet gebouwd.

## Interne componenten (C4-componentmodel)

De NMC bestaat intern uit de volgende componenten (gebaseerd op het C4-componentdiagram).
Geïmplementeerde componenten zijn vetgedrukt; de rest is toekomstig ontwerp.

| Component | Type | Omschrijving |
|---|---|---|
| **Centrale-regie-API** | REST (controller) | Inbound endpoint voor het centraal profiel: intake op identificerend nummer; NMC resolvet zelf de contactgegevens via de Profielservice. |
| **Afleverstatus-callback** | REST (controller) | Webhook waarop NotifyNL delivery receipts meldt. |
| **Aanname** | Service (`AannameService`) | Legt notificatie, eerste event en verzendtaak vast in één transactie en dwingt het quotum per Dienstverlener af. |
| **Verzendtaak** | Taakhandler (`VerzendTaakHandler`) | Haalt bij centrale regie het adres op, verstuurt via NotifyNL met het poging-id als `reference` en voert de overgangen uit. |
| **Profielservice-adapter** | Client | Haalt contactvoorkeur op bij de Profielservice en kan een e-mailadres invalideren. |
| **Verzendadapter** | Client (bearer-JWT) | Verstuurt berichten via NotifyNL (`template_id` + `personalisation`). |
| **Terugkoppeltaak** | Taakhandler (`TerugkoppelTaakHandler`, `WebhookDispatcher`) | Levert per dienstverlener de events vanaf de leverpositie als CloudEvents-batch aan de webhook uit het register, met een ondertekende bearer-JWT. |
| **Consument-callback-adapter** | Webhook-client (CloudEvents NL GOV) | Doet de POST naar de webhook: één poging, zonder herhaling. |
| **Verzendverwerker** | Worker (`TaakWorker`, `TaakClaimer`) | Claimt taken uit de takentabel met `SKIP LOCKED`, verdeeld over dienstverleners en binnen het verzendbudget, en geeft ze aan de handler per soort. |
| **notificatiedatabase** | PostgreSQL | Slaat notificaties met hun status, de verzendpogingen, het eventlog, de takentabel, het verzendbudget en het dienstverlenerregister op; een retentiejob verwijdert notificaties `notificatie.retentie.bewaartermijn` na de laatste overgang. |
| **Decentrale-regie-API** | REST (controller) | Inbound endpoint voor het decentraal profiel: intake op het meegegeven e-mailadres, zonder Profielservice-lookup. |
| Adres-adapter | Client | Haalt een postadres op bij KvK Handelsregister of BRP als fallback bij contactherstel. |
| Contactherstel-coordinator | Component | Coördineert de contactherselstroom bij onbereikbaarheid; initieert een nieuwe verzendpoging via een ander kanaal en meldt dit aan de Contactherstel-dienst. |

De externe systemen die de NMC aanroept of van ontvangt:

- **Dienstverlener / OMC** — initiëert notificaties (centraal of decentraal)
- **NotifyNL** — verzendt template-berichten en meldt afleverstatus terug
- **Profiel Service** — levert contactgegevens en -voorkeuren
- **Contactherstel** — voert de uiteindelijke contactherselactie uit (fysieke post via Printstraat)
- **KvK Handelsregister / BRP** — adresgegevens als fallback bij contactherstel

## Domeinmodel

Het datamodel volgt ADR 0024 (georkestreerde state machine met eventlog):

- **`notificatie`** draagt de status in de levenscyclus (`aangenomen`,
  `in-verzending`, `verzonden`, `bezorgd` en de eindstatussen), een eventuele
  `reden` en een `versie` die per overgang met één oploopt.
- **`poging`** is één verzending bij NotifyNL: nummer, status (de uitkomst uit de
  receipts), het NotifyNL-id en het tijdstip uit de laatst verwerkte receipt. Een
  receipt komt via `reference` (het poging-id) bij de poging terecht, en anders via
  het NotifyNL-id.
- **`event`** is het eventlog: één rij per overgang met van, naar, reden, het
  registratietijdstip en een volgnummer dat gelijk is aan de versie van de
  notificatie na de overgang. Elke rij draagt de transactie-id, zodat een latere
  feed het log op commitvolgorde kan lezen; het log is daarop gepartitioneerd,
  zodat het per partitie kan worden opgeruimd.
- **`dienstverlener`** is het register uit de onboarding; nu één rij uit de
  migratie, waarvan `dv_id` op elke notificatie en elk event staat. De optionele
  `webhook_url` en `webhook_max_mislukkingen` zet beheer; een registratie-API is er nog niet.
- **`taak`** draagt de bijwerkingen (verzenden, receipts verwerken, navraag,
  vaststellen, terugkoppelen, ongeldig melden, wissen, controle, onderhoud) met
  `due` als timer, een lease, een claim-epoch en een pogingenteller; per soort
  gepartitioneerd. Een worker claimt met `FOR UPDATE SKIP LOCKED`; elke wijziging
  daarna toetst het claim-epoch, zodat een worker die zijn lease verloor niets meer
  kan afronden. Na het maximum aantal mislukkingen staat een taak op `MISLUKT` en
  telt hij in de metriek `nmc_taken_mislukt`.
- **`verzendbudget`** is een rij per Notify-service per minuut met de resterende
  tokens; het verzenden en de navraag claimen ertegen, met een vast deel
  gereserveerd voor de navraag.
- **`bevestiging`** is per dienstverlener de feedcursor die hij zelf terugschrijft;
  hij gaat alleen vooruit.
- **`webhookpositie`** is per dienstverlener het laatst aan de webhook geleverde
  event (epoch, transactie-id, event-id), met het aantal mislukte leveringen op rij en
  een eventuele pauze. Het is een leverpositie, geen bevestiging.

`Overgangsfunctie` is de enige schrijver van de status. Hij vergrendelt de
notificatierij (`SELECT ... FOR UPDATE`), toetst de overgang aan
`Overgangsregels` en schrijft in dezelfde transactie het event. Een deferred
databasetrigger dwingt dat af voor elke schrijver, ook buiten Hibernate om: een
nieuwe rij begint op `aangenomen`, een nieuwe versie moet een toegestane overgang
zijn en in dezelfde transactie een event met die versie als volgnummer hebben.
De toegestane paren staan in de tabel `toegestane_overgang`; een test houdt die
gelijk aan `Overgangsregels`.

Twee tijdstippen blijven uit elkaar: het tijdstip uit de receipt
(`completed_at`, met `sent_at` en `created_at` als terugval) staat op de poging
en bepaalt de volgorde van receipts; het tijdstip op het event is de
registratietijd op de eigen klok. `laatste_status_update` op `notificatie` is het
tijdstip van de laatste statuswijziging; de retentiejob selecteert erop.

Die retentiejob (`NotificatieRetentieScheduler`) verwijdert een `Notificatie`
met zijn pogingen zodra die laatste statuswijziging ouder is dan de
geconfigureerde `notificatie.retentie.bewaartermijn` (zie
`application.properties`), ongeacht de status; de events blijven staan. De
termijn staat op **7 dagen**, conform de afspraak met de Belastingdienst, en
heeft geen default in de code: is hij niet gezet, dan faalt de applicatie bij
het opstarten. De job draait dagelijks om 03:00 Europese/Amsterdamse tijd
(`notificatie.retentie.cron`) en verwijdert in begrensde batches, elk geclaimd
met `FOR UPDATE SKIP LOCKED`, zodat meerdere pods de achterstand onder elkaar
verdelen. Een notificatie die verloopt zonder uitkomst (niet terminaal en niet
`bezorgd`) wordt per notificatie op WARN gemeld, in dezelfde transactie als de
verwijdering. De regel gebruikt `key=value` zodat er een dashboard op te bouwen
is:

```
Retentiejob: notificatie verlopen zonder eindstatus notificatieId=... status=VERZONDEN laatsteStatusUpdate=...
```

De detailregels zijn begrensd op 100 per run; het totaal in de afsluitende
samenvatting is dat niet. De job is een tussenstand: de wistaak en
onderhoudstaak uit ADR 0024 vervangen hem.

## API

De huidige endpoints zitten onder `/api/nmc/v1`:

- **`POST /centraal/notificaties`**: neemt de notificatie aan op een identificerend
  nummer; de verzendtaak haalt later het adres op bij de Profielservice.
  Retourneert `202` met `notificatieId` en `Location`, `400` bij een onbekend
  berichttype, en `429` als het quotum van de Dienstverlener voor vandaag bereikt is.
  Het veld `callbackUrl` bestaat niet meer; een aanroeper die het nog meestuurt krijgt
  geen fout, maar de URL wordt niet gebruikt.
- **`POST /decentraal/notificaties`**: idem voor een meegegeven e-mailadres (geen
  Profielservice-lookup). Retourneert `202`, `400` bij een onbekend berichttype of
  een ongeldig e-mailadres, en `429` bij het quotum.
- **`GET /notificaties/wijzigingen?cursor=&limiet=`**: de events van de eigen
  notificaties als CloudEvents, in commitvolgorde, met de cursor voor de volgende
  pagina. Zonder cursor vanaf het oudste beschikbare event. `400` bij een ongeldige
  cursor, `410` bij een vervallen cursor (begin dan zonder cursor), `429` bij de
  aanroeplimiet. Verwerk per `subject` op `sequence`: de feed garandeert geen
  volgorde binnen één notificatie.
- **`PUT /notificaties/wijzigingen/bevestiging`**: legt de cursor vast tot waar de
  Dienstverlener heeft verwerkt; een oudere cursor verandert niets. `204`, `400` of `410`.
- **Webhook** (in het contract als callback bij de feed): heeft de Dienstverlener een
  `webhook_url` in het register, dan levert één terugkoppeltaak per Dienstverlener
  dezelfde events als de feed, gebundeld per aanroep (`nmc.webhook.bundel`), als
  `application/cloudevents-batch+json`. Elke aanroep draagt de header `Nmc-Cursor`
  met de cursor van het laatste event in de bundel en een bearer-JWT (RS256, `iss` het
  NMC, `aud` de webhook-URL, 5 minuten geldig). Een `2xx` zet de leverpositie vooruit
  maar is geen bevestiging. Een mislukte levering komt later opnieuw vanaf dezelfde
  positie, met een oplopende wachttijd; na `webhook_max_mislukkingen` (default
  `nmc.webhook.max-mislukkingen`) mislukkingen op rij pauzeert de webhook met een
  wachttijd die verdubbelt tot ten hoogste een dag. Een geslaagde levering zet de
  teller terug. De URL gaat vóór elke levering door `CallbackUrlValidator`; een
  ongeldige URL pauzeert de webhook zonder aanroep en geeft een ERROR in het log.
- **`GET /.well-known/jwks.json`**: de publieke sleutel waarmee de Dienstverlener de
  JWT op de webhook controleert, gekozen op `kid`.
- **`POST /notifynl-callback`**: webhook waarop NotifyNL de bezorgstatus
  (delivery receipt) van een verzending terugmeldt. Beveiligd met een bearer
  token dat geconfigureerd wordt in NotifyNL's dashboard en via
  `notify.callback.bearer-token` in de NMC. `ReceiptVerwerker` zoekt de poging
  op `reference` en anders op het NotifyNL-id, vergrendelt de notificatie en leest de poging daarna
  opnieuw, zodat twee gelijktijdige receipts elkaars uitkomst zien. Receipts komen
  at-least-once en ongeordend binnen; de volgorde komt uit het tijdstip in de
  receipt, begrensd op de eigen klok, en een herhaalde of oudere receipt verandert
  niets. De uitkomst gaat op de poging; daarna volgt de overgang van de
  notificatie als die vanuit de huidige status is toegestaan (een eindstatus is
  absorberend, behalve `bezorgstatus-onbekend`; `bezorgd` is geen eindstatus). Tussenstatussen van NotifyNL worden genegeerd en
  een onbekende status wordt op ERROR gelogd zonder iets te wijzigen. Een
  uitgevoerde overgang staat als event in het log, en komt zo in de feed en op de
  webhook, met `sequence` het volgnummer waarop de Dienstverlener ordent. Retourneert `204`
  op succes, ook als er niets veranderde, `401` bij een ontbrekend of ongeldig
  bearer token, en `404` als het NotifyNL-id bij geen poging hoort. Dit endpoint heeft een eigen, losse OpenAPI-specificatie (zie
  hieronder), zodat het makkelijk te verwijderen is zodra de NMC publiek
  bereikbaar is en NotifyNL een echte callback-URL kan benaderen.

Gepland/toekomstig (nog niet aanwezig):

- **`GET /centraal/notificaties/{id}`**: status van een eerder verstuurde notificatie
  opvragen.
- **`POST /centraal/notificaties/{id}/contactherstel`**: een nieuwe verzendpoging
  (contactherstel) starten voor een bestaande notificatie.

De `openapi.yaml`-specificatie (`/centraal/notificaties` en `/decentraal/notificaties`)
is beschikbaar via `/q/swagger-ui` wanneer de applicatie draait (`./mvnw quarkus:dev`);
zie de toelichting bij
"OpenAPI-specificatie & codegen" hieronder voor waarom `/notifynl-callback`
daar niet in staat.

## OpenAPI-specificatie & codegen

Het contract van `/api/nmc/v1` is **spec-first** en bestaat uit twee losse
specificaties:

- `src/main/resources/META-INF/openapi.yaml` — `POST /centraal/notificaties` en
  `POST /decentraal/notificaties` (de centrale- en decentrale-regie-flow).
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
  (`nl.rijksoverheid.moz.nmc.api.CentraleNotificatiesApi` en `DecentraleNotificatiesApi`,
  `nl.rijksoverheid.moz.nmc.notifynlcallback.api.NotifyNlCallbackApi`) en de
  request/response-modellen (`nl.rijksoverheid.moz.nmc.api.model.*`,
  `nl.rijksoverheid.moz.nmc.notifynlcallback.api.model.*`) in
  `target/generated-sources/openapi` (niet ingecheckt).
  `CentraleNotificatieController` en `DecentraleNotificatieController` (package
  `controller`) en `NotifyNLCallbackController` (package
  `notifynlcallback.controller`) implementeren de gegenereerde interfaces.

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
In test mode (`%test`) start de testsuite zelf een embedded PostgreSQL. Voor productie moeten
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
- `nmc.webhook.jwt.private-key` en `nmc.webhook.jwt.key-id`: de RSA-sleutel
  (PKCS#8-PEM, minstens 2048 bits) waarmee de NMC de JWT op de webhook ondertekent,
  en de sleutel-id in de JWKS. Leeg in de repository; zonder waarde start de
  applicatie niet op. Lokaal een sleutel maken met
  `openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048`.

Start de applicatie in dev mode:

```shell
./mvnw quarkus:dev
```

Voor `./mvnw test` worden de Profielservice- en NotifyNL-clients gemockt en levert
de webhook aan een testendpoint in de applicatie zelf; hiervoor is geen draaiende
externe service nodig.

Bovenstaande draait de app in **dev-mode** (`%dev`-profiel: Postgres uit
`podman compose`, Flyway migreert automatisch). Wil je in plaats daarvan de
**container-image** lokaal bouwen en draaien (prod-profiel, zoals op ZAD), zie
[`docs/lokaal-testen.md`](docs/lokaal-testen.md).

## Status & vervolgstappen

De NMC implementeert de centraal- en decentraal-profiel happy-flows inclusief de
asynchrone bezorgstatus, de eventfeed en de webhook, zoals beschreven onder
"Geïmplementeerde functionaliteit". De aanname is asynchroon (202) met een
verzendtaak; de navraagtaak wordt gepland maar nog niet uitgevoerd. Nog **niet**
aanwezig:

- **Navraag** bij NotifyNL en **vaststelling** van de bezorging
- **Contactherstel** en **herverzending** (voor beide profielen)
- Een koppeling met de **Templating Service** (het `template_id` wordt voorlopig
  bepaald door een lokale `BerichtType`-enum, niet via een externe Templating Service)
- **`GET /centraal/notificaties/{id}`** voor de status van één notificatie
- Een **registratie-API** voor de webhook
- Een uitgewerkt **observability-koppelvlak**

