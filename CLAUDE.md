# CLAUDE.md

Richtlijnen voor het werken aan de NotificatieManagementComponent (NMC).

De README beschrijft wat de component doet, hoe je hem lokaal draait en hoe de
API eruitziet. Dit bestand beschrijft wat je moet weten om er wijzigingen in aan
te brengen zonder iets stilzwijgend te breken: het domein, de regels, de gates en
de plekken waar de build je niet waarschuwt.

## Taal

Communicatie, commentaar en commitmessages in het Nederlands.

De grens tussen Nederlands en Engels loopt door de code heen:

- **Domeinbegrippen blijven Nederlands**, ook als identifier: `Notificatie`,
  `Dienstverlener`, `BerichtType`, `verwerkAfleverstatus`, `CallbackUrlValidator`.
- **Vast technisch idioom blijft Engels.** Adapter, filter, retry, callback,
  factory, repository. Vertalen maakt die termen minder herkenbaar, niet meer.
- **Testnamen beschrijven het gedrag in het Nederlands**, in de vorm
  `methode_situatie_verwachting`: `lever_geen2xx_gooitLeveringExceptionNaEenAanroep`,
  `interneHostnaam_wordtGeweigerd`.

## Wat de NMC is

Deze repo bevat **alleen de NMC**: de centrale hub die een notificatieverzoek van
een aanroeper aan Dienstverlener-zijde aanneemt, het bericht samenstelt (via een
aparte Templating Service), het via NotifyNL verstuurt, de afleverstatus verwerkt
en, als digitale bezorging mislukt, **contactherstel** regisseert (terugvallen op
een fysieke brief via Printstraat/Postadres).

Al het andere (OMC, Profielservice, Templating Service, NotifyNL, KvK/BRP/NHR,
DCProfiel) is een apart systeem met een eigen repo. De NMC standaardiseert logica
die Dienstverleners anders ieder zelf zouden moeten bouwen.

### Begrippen

- **Dienstverlener**: de overheidsorganisatie die de notificatie verstuurt.
- **Procesapplicatie**: de backend van de Dienstverlener die besluit dat er een
  notificatie nodig is.
- **OMC (Output Management Component)**: een component per
  Dienstverlener, vergelijkbaar met de NMC maar beperkt tot één Dienstverlener.
  Veel Dienstverleners draaien er al een; de NMC standaardiseert wat anders per
  OMC gedupliceerd wordt. De OMC is de gebruikelijke aanroeper van de NMC, maar een
  dienst of voorziening zonder OMC kan de NMC ook rechtstreeks aanroepen.
- **DCProfiel**: de eigen, decentrale contactvoorkeurenopslag van een
  Dienstverlener, gebruikt door de OMC.
- **Profielservice**: `../moza-profiel-service` (Quarkus). Centrale profiel- en
  voorkeurenservice: vertaalt BSN/KVK/RSIN naar contactvoorkeuren en
  contactgegevens, beheert opties voor contactherstel en kan een e-mailadres
  ongeldig laten verklaren.
- **Templating Service**: bestaat nog niet. Levert berichtsjablonen per berichttype.
- **NotifyNL**: de Nederlandse fork van GOV.UK Notify. Het feitelijke
  verzendkanaal voor e-mail en (via Printstraat) fysieke post.
- **Printstraat / Postadres**: printen en postbezorging, bereikt via NotifyNL.
- **KvK/BRP/NHR**: externe registraties (Kamer van Koophandel, Basisregistratie
  Personen, Nieuw Handelsregister), bron voor een terugvaladres bij contactherstel.
- **Contactherstel**: terugvallen op een ander contactkanaal (meestal een fysieke
  brief) wanneer digitale bezorging mislukt.
- **Herverzending**: hetzelfde kanaal opnieuw proberen na een fout.
- **wMEBV**: de *Wet modernisering elektronisch bestuurlijk verkeer*, de wet achter
  de bewijslast van bezorging en de plicht tot contactherstel.
- **BSN / KVK / RSIN**: identificatienummers van burger, onderneming en
  rechtspersoon.

### De twee onafhankelijke assen

Deze assen staan los van elkaar; haal ze niet door elkaar.

**As 1: zit er een OMC vóór de NMC?**

- Ja: de OMC roept aan; de NMC meldt de status terug aan de OMC, die hem
  doorgeeft aan de Procesapplicatie.
- Nee: een dienst of voorziening zonder eigen OMC roept de NMC rechtstreeks aan.

**As 2: profieltype centraal of decentraal.** Dit is de as die de logica van de
NMC werkelijk verandert.

- **Centraal profiel: de NMC heeft de regie.** De aanroeper levert alleen een
  identificatie (BSN/KVK/RSIN + Dienstverlener + dienst + berichttype +
  berichtspecifieke gegevens). De NMC:
  - vraagt de contactvoorkeur en contactgegevens op bij de Profielservice;
  - laat bij een mislukte bezorging het e-mailadres ongeldig verklaren, vraagt
    de Profielservice om opties voor contactherstel en valt terug op KvK/BRP/NHR
    voor een adres als de Profielservice niets heeft;
  - voert het hele contactherstel zelf uit ("volledige ontlasting").
- **Decentraal profiel: de NMC is doorgeefluik.** De OMC heeft contactvoorkeur en
  contactgegevens al via zijn eigen DCProfiel bepaald en levert de NMC het
  volledige beeld (onder meer e-mailadres, verzendtype en berichttype). De NMC:
  - stelt samen en verstuurt zoals opgedragen;
  - meldt bij een mislukte bezorging de uitkomst terug aan de OMC, die zijn
    DCProfiel zelf bijwerkt;
  - voert contactherstel alleen uit als de OMC daartoe besluit
    ("gedeeltelijke ontzorging").

Een aanwezige OMC betekent **niet** automatisch decentraal profiel: een OMC kan
ook alleen een identificatie doorgeven en de NMC de regie laten.

### Fasen van de flow (vanuit de NMC gezien)

1. **Ontvangstvoorkeur en contactgegevens bepalen**: via de Profielservice
   (centraal) of kant-en-klaar van de aanroeper (decentraal).
2. **Samenstellen en versturen**: sjabloon ophalen bij de Templating Service,
   vullen en aanbieden bij NotifyNL.
3. **Antwoord vastleggen en verwerken**: de afleverstatus van NotifyNL verwerken,
   het e-mailadres ongeldig laten verklaren of de OMC informeren, besluiten over
   herverzending.
4. **Gegevens voor contactherstel bepalen**: centraal via de Profielservice met
   KvK/BRP/NHR als terugval; decentraal aangeleverd door de aanroeper.
5. **Contactherstel uitvoeren en opvolgen**: digitaal (dezelfde flow van sjabloon,
   verzending en antwoord) of fysiek (NotifyNL → Printstraat → Postadres), en de
   status terugmelden.

## Wat er nu staat

Geïmplementeerd:

- `POST /api/nmc/v1/centraal/notificaties`: neemt een notificatie aan op een
  identificerend nummer en antwoordt 202 met `Location`; de verzendtaak haalt het
  e-mailadres later op bij de Profielservice.
- `POST /api/nmc/v1/decentraal/notificaties`: idem met een e-mailadres van de
  aanroeper; geen Profielservice-lookup.
- `POST /api/nmc/v1/notifynl-callback`: ontvangt de afleverstatus van NotifyNL,
  beveiligd met een bearer token (`NotifyNLCallbackAuthFilter`). `InkomendEventOpslag`
  slaat de receipt op als `RECEIPT_VERWERKEN`-taak (uniek op poging, status en
  tijdstip) en de controller antwoordt 204, ook bij een receipt die bij geen poging
  hoort (geteld in `nmc.receipts.afgewezen`). `ReceiptTaakHandler` verwerkt hem.
- **Statusmodel uit ADR 0024.** `notificatie` draagt de status en een versie,
  `poging` de uitkomst per verzending, `event` het eventlog met één rij per
  overgang. `Overgangsfunctie` is de enige schrijver van de status; een deferred
  databasetrigger weigert een nieuwe versie zonder event of een niet-toegestane
  overgang.
- **Webhook als terugkoppeltaak.** Heeft een dienstverlener een `webhook_url` in het
  register, dan levert één `TERUGKOPPELEN`-taak per dienstverlener
  (`TerugkoppelTaakHandler`) de events na `webhookpositie` onder het watermerk van de
  feed als CloudEvents-batch aan die URL (`WebhookDispatcher`, `ConsumentCallbackAdapter`),
  met de header `Nmc-Cursor` en een RS256-bearer-JWT (`WebhookSleutel`; publieke sleutel
  op `GET /api/nmc/v1/.well-known/jwks.json`). Zie de integratie-aanname hieronder.
- **Adresselectie in de Profielservice-respons** (`ProfielServiceAdapter`): eerst
  een e-mailadres met exact de scope Dienstverlener + dienst, dan een met alleen
  de Dienstverlener als scope, dan het default-adres, dan een adres zonder scopes.
- **Takentabel en worker-lus.** `taak` (per soort gepartitioneerd) draagt de
  bijwerkingen met `due` als timer; `TaakClaimer` claimt per ronde met
  `FOR UPDATE SKIP LOCKED`, verdeeld over de dienstverleners met werk en voor het
  verzenden en de navraag binnen het `verzendbudget` per Notify-service per minuut.
  `TaakWorker` draait per soort een `@Scheduled`-ronde en geeft elke taak aan de
  `TaakHandler` van die soort. Handlers: `VerzendTaakHandler`, `ReceiptTaakHandler`,
  `Reconciler` (navraag), `BezorgingVaststellenTaakHandler`, `ControleTaakHandler` en `TerugkoppelTaakHandler`; de
  overige soorten worden gepland maar nog niet uitgevoerd.
- **Herverzending en `geldig_tot`.** Een notificatie krijgt één herverzending: een
  `temporary-failure` of `technical-failure` op de eerste poging plant een verzendtaak
  na `Verzendbeleid.herverzendWachttijd`, en de claim daarvan maakt de tweede poging aan
  (`verzonden` naar `verzonden`, met event). Op de tweede poging is een fout terminaal.
  `geldig_tot` (aanname plus `Verzendbeleid.geldigheid` van het berichttype) begrenst het
  starten van een poging: daarna eindigt de notificatie in `verlopen`, ook na een uitstel;
  een poging die al bij NotifyNL ligt loopt door. `ONGELDIG_MELDEN` bestaat als taaksoort
  maar wordt niet gepland: de Profielservice kent die operatie nog niet.
- **Navraag en vaststellen.** De verzend-commit plant per poging een navraagtaak volgens
  `Navraagschema` (`nmc.navraag.*`); `Reconciler` vraagt de status op bij NotifyNL en
  geeft een uitkomst aan `ReceiptVerwerker`. Een 404 of het einde van de bewaartermijn
  van NotifyNL zet de poging op `ONBEKEND` en de notificatie op `bezorgstatus-onbekend`.
  Een verwerkte receipt rondt de navraag af. `delivered` plant een vaststeltaak op het
  tijdstip uit de receipt plus `Vaststellingstermijn` (`nmc.vaststelling.*`); een
  faalreceipt binnen de termijn rondt die af, een faalreceipt met een tijdstip daarna
  wordt alleen op de poging vastgelegd. De termijn loopt vanaf `poging.bezorgd_op`, dat een
  latere faalreceipt niet overschrijft.
- **Eén geconfigureerde dienstverlener.** `dienstverlener` heeft één rij uit de
  migratie; `DvProvider` levert die `dv_id`, die op elke notificatie en elk event
  staat. Tokenvalidatie per dienstverlener vervangt later alleen de provider.
- **Eventfeed met bevestiging.** `GET /api/nmc/v1/notificaties/wijzigingen` leest het
  eventlog van de dienstverlener op (transactie-id, event-id) onder het watermerk
  `pg_snapshot_xmin(pg_current_snapshot())`, zodat een cursor nooit een event overslaat
  dat later committet dan een event met een hogere transactie-id. De cursor is
  base64url van `epoch:xid:eventId`; een ander epoch (`nmc.feed.cluster-epoch`) of een
  positie onder het oudste event geeft 410. `PUT .../wijzigingen/bevestiging` schrijft
  `bevestiging`, alleen vooruit. De aanroeplimiet (`Aanroeplimiet`) telt per pod.

Beide intakes gaan via `AannameService`: notificatie op `aangenomen` met event 0, de
versleutelde payload en een verzendtaak in één transactie; het quotum per
dienstverlener per dag (`dienstverlener.quotum_per_dag`) geeft 429.
`AannameResponseFilter` maakt van het antwoord een 202 met `Location`, omdat de
gegenereerde interface geen `Response` teruggeeft. `VerzendTaakHandler` doet in een
eerste transactie `aangenomen` naar `in-verzending` met een geplande poging, roept
daarbuiten de Profielservice (bij centrale regie) en NotifyNL aan met het poging-id
als `reference`, en doet in een tweede transactie `in-verzending` naar `verzonden`
met een navraagtaak. Een `ValidationError` van NotifyNL op `email_address` en een
partij zonder adres zijn terminaal (`niet-bezorgbaar`). Een andere 4xx, behalve
429, ligt aan het NMC en kost een poging; 429, 5xx, een verbindingsfout en een
ontbrekende KEK-versie stellen de taak uit zonder poging. Een herclaim zoekt eerst
op `reference`. `ControleTaakHandler` plant zichzelf opnieuw, geeft een notificatie
zonder taak de taak die bij haar status hoort en plant een terugkoppeltaak voor
elke dienstverlener met een webhook die er nog geen heeft. De
verzendtaak schrijft nog geen LDV-registratie: de `@Logboek`-interceptor werkt alleen
binnen een REST-aanroep.

Nog niet gebouwd: een endpoint om de status op te vragen, contactherstel, de
Templating Service-koppeling (templates staan als vaste NotifyNL-template-IDs in
`BerichtType`), de OMC-koppeling, de orkestratie die de fasen aan elkaar knoopt, en
de logica die kiest tussen herverzending en contactherstel.

## Integratie-aannames

- **Versturen bij NotifyNL is synchroon, de afleverstatus asynchroon.** Het
  verzoek is een synchrone POST (JWT gebouwd uit de API-key) die alleen bevestigt
  dat NotifyNL het bericht heeft aangenomen, niet dat het bezorgd is. De echte
  uitkomst komt later via de callback. Die callback-URL en het bijbehorende bearer
  token stel je eenmalig per service/API-key in NotifyNL's dashboard in ("API
  integration" → "Callbacks"); ze zijn geen veld op het verzendverzoek. Het token
  moet gelijk zijn aan `notify.callback.bearer-token`.
- **Wanneer herverzending en wanneer contactherstel** valt buiten de huidige
  scope. Laat daar een uitbreidingspunt voor open en bouw het niet nu.
- **Templating Service** bestaat nog niet; een eenvoudige mock met een vaste
  template volstaat, lage prioriteit.
- **Observability-koppelvlak** (verwerkings- en statusinformatie voor
  Dienstafnemers, waarschijnlijk voor de wMEBV-bewijslast) heeft nu geen
  prioriteit. Het eventlog `event` is het audittrail: één rij per overgang, met het
  volgnummer gelijk aan de versie van de notificatie.
- **Elke schrijver van de status gaat via `Overgangsfunctie`.** Die vergrendelt de
  `notificatie`-rij (`PESSIMISTIC_WRITE`), toetst de overgang aan `Overgangsregels`,
  verhoogt de versie met precies één en schrijft het event, in de transactie van de
  aanroeper. De deferred constraint trigger `notificatie_overgang` (V5) bewaakt ook
  schrijvers buiten Hibernate, maar controleert minder: een nieuwe rij begint op
  `AANGENOMEN`, en een nieuwe versie moet een toegestane overgang zijn met in dezelfde
  transactie een event met die versie als volgnummer. Een stap van precies één en de
  vergrendeling dwingt hij niet af. Let op: elke update van een
  `notificatie`-rij via Hibernate verhoogt `@Version` en vraagt dus een event. Een
  schrijver die geen overgang doet (bijvoorbeeld het wissen van een sleutel) moet
  de versie buiten beschouwing laten. In tests zet je de trigger uit met
  `SET LOCAL session_replication_role = replica`; de databasefout van een deferred
  trigger zit bij de commit als suppressed exception onder de `RollbackException`.
- **Statussen.** `NotificatieStatus` (tien waarden, levenscyclus) en `PogingStatus`
  (zeven waarden, uitkomst per verzending) staan als `CHECK`-constraint in V4; een
  nieuwe waarde vraagt dus ook een migratie, en voor `NotificatieStatus` een regel in
  `toegestane_overgang` en `Overgangsregels`. In de API en het CloudEvent gaan
  statussen en redenen als kebab-case (`toApiValue`).
- **Een test rekt zichtbaarheid hooguit op tot package-private.** `public` is nooit een
  testkeuze: dat maakt van een implementatiedetail een belofte aan elke aanroeper.
  Package-private mag wel, het blijft binnen het package en de compiler bewaakt het, en
  bij zo'n seam staat een comment met de reden. Moet je meer dan een handvol leden openen,
  haal er dan een klasse uit (zoals `RetentieBatch`) in plaats van de bestaande verder open
  te zetten. Reflectie om private leden te bereiken is geen alternatief.
- **Queries staan in een repository, niet in een entiteit of een service.** De JPQL van
  de retentiejob staat als `@NamedQuery` op `Notificatie`, want JPA kent geen andere plek
  en Hibernate controleert ze daar bij het opstarten; uitvoeren gebeurt uitsluitend in
  `NotificatieRepository`. Entiteiten krijgen geen finders en geen native SQL, en
  `getEntityManager()` verlaat de repository niet. Tests mogen wél rechtstreeks SQL
  schrijven wanneer ze een toestand nodig hebben die de overgangsfunctie niet maakt; die
  statements staan gebundeld in `NotificatieFixtures`, die de trigger voor de eigen
  transactie uitzet.
  `moza-portaal/dependencies/omc/swagger.json` bevat een
  `DeliveryReceipt`/`DeliveryStatuses`-schema, maar dat hoort volgens Joeri
  waarschijnlijk bij een ander systeem dan de OMC per Dienstverlener (mogelijk een
  Output Management Systeem of Printstraat). Gebruik die swagger niet als contract
  voordat dat is opgehelderd.
- **Afleverstatussen komen at-least-once en ongeordend binnen.** NotifyNL biedt een
  callback opnieuw aan bij elke niet-2xx; de NMC antwoordt 2xx zodra de receipt is
  opgeslagen, en een fout in de verwerking laat de taak staan. `ReceiptVerwerker` ordent per poging op het
  tijdstip in de receipt (`completed_at`, met `sent_at` en `created_at` als terugval,
  begrensd op de eigen klok); een herhaalde of oudere receipt verandert niets. De
  poging wordt pas na het vergrendelen van de notificatie opnieuw gelezen, zodat een
  receipt die op de vergrendeling wachtte de uitkomst van de eerste ziet. Een faalreceipt
  voor een poging die niet meer de laatste is, geeft geen overgang.
  Tussenstatussen worden genegeerd; een onbekende status wordt op ERROR gelogd en
  verandert niets.
- **Receipttijd en registratietijd zijn gescheiden.** Het tijdstip uit de receipt staat
  op `poging.receipt_tijdstip` en is het tijdstip voor het afleverbewijs; `event.tijdstip`
  en `notificatie.laatste_status_update` staan op de eigen klok.
- **De webhook leest het eventlog; er is geen push per overgang.** Een
  `TERUGKOPPELEN`-taak per dienstverlener (`notificatie_id` leeg) plant zichzelf steeds
  opnieuw. De eerste plant `ControleTaakHandler`, dus een nieuwe `webhook_url` gaat
  binnen `nmc.controle.interval` leveren; een unieke index houdt het op één taak per
  dienstverlener, zodat met de claim nooit twee pods tegelijk aan dezelfde
  dienstverlener leveren. Per ronde: lees in een transactie register, positie en
  events (`Eventfeed.leesVoorWebhook`, een positie uit een ander epoch begint bij het
  oudste event), verleng de lease, POST buiten de transactie, en zet in één transactie
  met `TaakClaimer.stelUit` (epoch-getoetst) de positie vooruit of de mislukking vast.
  Een mislukte levering is een externe fout en kost de taak geen poging. Wachttijd:
  `nmc.webhook.herpoging-wachttijd` verdubbeld per mislukking; vanaf
  `webhook_max_mislukkingen` (default `nmc.webhook.max-mislukkingen`) is de webhook
  gepauzeerd met `nmc.webhook.pauze-wachttijd` verdubbeld per verdere mislukking, tot
  ten hoogste een dag. Een ongeldige URL (`WebhookUrlControle`, via
  `CallbackUrlValidator`) pauzeert direct, zonder aanroep, met een ERROR. Een `2xx` is
  geen bevestiging; die blijft de feedcursor. Zonder `webhook_url` rondt de taak af.
  In tests laat `LokaleWebhookUrlControle` (`@Mock`) de http-URL van `WebhookOntvanger`
  op localhost toe.
- **Webhook en feed leveren hetzelfde CloudEvent.** `NotificatieStatusEvent.van` bouwt
  beide; `id` is het event-id uit het eventlog. `xid` staat niet op de entity `Event`:
  de feed leest hem met native SQL via `xid::text::bigint`, omdat `xid8` geen cast naar
  `bigint` kent. Om dezelfde reden is `webhookpositie`, net als `bevestiging`, geen
  entity maar native SQL in een repository.
- **`NotificatieRetentieScheduler` ruimt verlopen notificaties op**, in batches met een
  eigen transactie per batch, `notificatie.retentie.bewaartermijn` na de laatste overgang
  (`laatste_status_update`) en los van de status en van de levering aan de
  Dienstverlener. De batch claimt met `FOR UPDATE SKIP LOCKED`, omdat er in
  productie minimaal drie pods draaien. De pogingen gaan mee via de foreignkey; de events
  blijven staan. Een notificatie die verloopt zonder uitkomst (niet terminaal en niet
  `BEZORGD`) wordt apart op WARN gemeld voordat de rij weggaat. Dit is een tussenstand:
  de wistaak en onderhoudstaak uit ADR 0024 vervangen deze job.

## Technische stack

- **Runtime:** Quarkus 3.39.1, Java 25
- **Build:** één Maven-module, wrapper `./mvnw`
- **API:** contract-first uit `META-INF/openapi.yaml` en
  `META-INF/notifynl-callback-openapi.yaml`, Quarkus REST + Jackson
- **Uitgaande clients:** quarkus-openapi-generator voor Profielservice en
  NotifyNL; een dynamisch gebouwde REST-client voor de webhook van de dienstverlener
- **Persistentie:** PostgreSQL 18 + Hibernate ORM Panache + Flyway; in tests een embedded PostgreSQL (Zonky)
- **Test:** JUnit 5, REST-assured, Mockito, Jazzer (fuzzing)
- **Fouten:** RFC 9457 `application/problem+json` via quarkus-http-problem
- **Audit:** LDV-wrapper (`logboekdataverwerking`), standaard uitgeschakeld
- **Image:** jib (geen Dockerfile), `eclipse-temurin:25-jre`, draait als uid 1001
- **Health:** smallrye-health op `/q/health`; readiness bevat een datasource-check

Packages zijn **technisch** ingedeeld: `controller/`, `service/`, `domain/`,
`repository/`, `client/<systeem>/`, `helper/`. De uitzondering is
`notifynlcallback/`, dat een eigen `controller/` en `filter/` heeft omdat het uit
een eigen contract komt. Volg die indeling.

## Contract-first

Er zijn twee servercontracten, elk met een eigen execution van de
`openapi-generator-maven-plugin` (`jaxrs-spec`, `interfaceOnly=true`,
`returnResponse=false`):

| Contract | Interfaces en modellen in |
|----------|---------------------------|
| `src/main/resources/META-INF/openapi.yaml` | `nl.rijksoverheid.moz.nmc.api(.model)` |
| `src/main/resources/META-INF/notifynl-callback-openapi.yaml` | `nl.rijksoverheid.moz.nmc.notifynlcallback.api(.model)` |

Annotatie-scanning staat uit (`mp.openapi.scan.disable=true`); Quarkus publiceert
het statische `META-INF/openapi.yaml` op `/q/openapi`.

**Schrijf geen DTO met de hand en bewerk niets onder `target/generated-sources`.**
Pas het contract aan en draai de build opnieuw.

De controllers implementeren de gegenereerde interfaces. De interface draagt pad,
HTTP-methode, mediatypes en bodyvalidatie; de controller alleen de implementatie.
**Zet geen JAX-RS-annotatie (`@POST`, `@Path`, `@Consumes`, `@Produces`, `@Valid`)
op een controllermethode**: volgens de JAX-RS-overervingsregel vervallen dan alle
annotaties van de interface voor die methode. Anders dan in de profiel-service
bewaakt hier geen test dat.

Aandachtspunten in de contracten:

- **Validatie die de generator niet kan uitdrukken** gaat via
  `x-field-extra-annotation`. Let op bij `format: uri`: dat wordt een `java.net.URI`,
  waar Hibernate Validator geen `@Size` voor heeft (HV000030).
- **De webhook staat als callback bij `GET .../wijzigingen`**, met als sleutel
  `{webhookUrl}`: OpenAPI 3.0 kent geen losse webhooks, en de URL komt uit het register.
- **`IdentificatieType`** is via `importMappings` gekoppeld aan de bestaande enum
  in `controller/`, zodat er geen tweede klasse ontstaat.
- **De lijst geldige berichttypes staat op twee plekken**: in de `description` van
  `berichtType` in het contract en in de enum `BerichtType`. Houd ze gelijk.
- **Contractversie op twee plekken**: `info.version` in `openapi.yaml` en
  `mp.openapi.info.version` in `application.properties`, die `ApiVersionFilter`
  als `API-Version`-header meestuurt. Geen test controleert dat ze gelijk zijn.
- **Bewust geen `servers:`-blok**, zodat "Try it out" in Swagger UI op elke
  omgeving naar de eigen host wijst.

De clientcontracten staan in `src/main/resources/openapi/`
(`profielservice_api.json`, `notifynl_api.yaml`). quarkus-openapi-generator bouwt
daaruit de clients in `client/profielservice/generated` en
`client/notifynl/generated`. Wijzigt het contract van de Profielservice, werk dan
dit bestand bij; het wordt niet automatisch gesynchroniseerd.

NotifyNL krijgt per aanroep een vers JWT: `NotifyNLVerzendAdapter` bouwt het met
`NotifyNLJwtFactory` en zet het in `NotifyNLAuthorizationHolder`, waarna
`NotifyNLCredentialsProvider` (een `@Alternative` op de standaard
credentialsprovider van de extensie) het als bearer token meegeeft.

## Build en test

Java en Maven staan **niet op `PATH`**; ze komen uit SDKMAN. Source die eerst:

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
```

```bash
podman compose up -d          # PostgreSQL voor dev-mode (docker compose werkt ook)
./mvnw quarkus:dev            # live reload op http://localhost:8080
./mvnw verify                 # volledige suite incl. coverage-gate; embedded PostgreSQL, geen containers nodig
```

Tests hebben geen containers nodig: `EmbeddedPostgresTestResource` start een echte
PostgreSQL 18 als kindproces van de test-JVM (Zonky) en geeft url en credentials aan
Quarkus door; Flyway migreert die database bij het opstarten en `validate` controleert
het schema tegen de entities. Elke `@QuarkusTest` deelt die ene database, dus een test
ruimt zijn eigen rijen op. Tests die zonder Quarkus tegen de migraties werken
(`V2MigratieTest`) starten hun eigen embedded PostgreSQL. Profielservice en NotifyNL
worden gemockt; de webhook levert aan het testendpoint `WebhookOntvanger`.

Dev-mode draait standaard op poort 8080, en `%dev.quarkus.rest-client.profielservice.url`
wijst óók naar `http://localhost:8080`. Draai je een echte Profielservice lokaal,
geef dan één van beide een andere poort. Beide compose-bestanden claimen ook
hostpoort 5432.

Alle tests draaien onder Surefire; Failsafe wordt overgeslagen (`skipITs=true`).

Hoe je de container-image lokaal bouwt en draait staat in `docs/lokaal-testen.md`.

### Coverage

JaCoCo-gate: **90% instructies en 75% branches** op
BUNDLE-niveau, met `**/api/**` en `**/generated/**` uitgesloten. `pom.xml` is
leidend voor die getallen; controleer ze daar voordat je erop bouwt.

Twee dingen die hier vaak misgaan:

- **De gate hangt aan fase `verify`, niet `package`.** `maven.yml` draait daarom
  `mvn -B verify`. `./mvnw package` of `./mvnw test` controleert de dekking niet;
  draai lokaal `./mvnw verify` om te zien of CI groen wordt. De job heet
  `Maven verify` en is een verplichte status check op `main`: een rode build
  blokkeert de merge. Hernoem die job niet zonder de branch protection mee aan te
  passen, anders wacht elke PR op een check die nooit komt.
- **De excludelijst staat op twee plekken**: in de `jacoco-maven-plugin`-configuratie
  in `pom.xml` en als `%test.quarkus.jacoco.excludes` in `application.properties`.
  Houd ze letterlijk gelijk. De gate leest de lijst uit `pom.xml`, dus lopen ze
  uiteen, dan blijft de build groen en merk je het niet.

`quarkus-jacoco` is test-scoped. Zet de `quarkus.jacoco.*`-properties daarom
alleen onder `%test`: in het prod-image bestaat de extensie niet en geeft een
onbekende sleutel een fout bij het starten van de container.

### Fuzzing

`fuzzing/EndpointFuzzTest` is een `@QuarkusTest` met `@FuzzTest`-methodes. Zonder
`JAZZER_FUZZ` in de omgeving draaien ze in de gewone suite als regressietest over
de opgeslagen corpus. De standalone targets (`EndpointFuzzer`,
`BerichtTypeFuzzer`, `HashHelperFuzzer`, `JsonDeserializationFuzzer`) bouwt
ClusterFuzzLite via `.clusterfuzzlite/build.sh`; zie de `cflite_*`-workflows.

## Database en migraties

- **Migraties zijn onveranderlijk na toepassing.** Wijzig een bestaande `V*.sql`
  nooit; voeg een `V(N+1)__...sql` toe in `src/main/resources/db/migration/`. Flyway
  bewaart een checksum per script: een gewijzigd script laat de migratie falen op een
  database waar het al gedraaid heeft, ook op een previewcluster.
- **Expand/contract bij een kolomwijziging.** Voeg eerst de nieuwe kolom toe, vul hem
  in dezelfde migratie uit de oude, en laat de `DROP COLUMN` pas volgen als niets hem
  meer leest. V4 (expand), V5 (overstap en backfill) en V6 (contract) doen dat voor de
  overstap naar het statusmodel uit ADR 0024; V8 voegt `dienstverlener`, `taak` en
  `verzendbudget` toe en maakt `dv_id` op `notificatie` en `event` verplicht.
- **Gepartitioneerde tabellen (`event`, `taak`) hebben de partitiesleutel in de primary
  key.** Een query op `taak` noemt daarom altijd de `soort`, anders zoekt PostgreSQL in
  elke partitie; een nieuwe `TaakSoort` vraagt een migratie met een nieuwe partitie.
- **SQL is PostgreSQL 18.** Dev, test en prod draaien hetzelfde dialect, dus
  PostgreSQL-eigen SQL is toegestaan en draait ook in de tests.
- **Een index op een gevulde tabel gaat met `CREATE INDEX CONCURRENTLY`.** Een gewone
  `CREATE INDEX` neemt op PostgreSQL een `SHARE`-lock: `SELECT` blijft werken, maar elke
  `INSERT`, `UPDATE` en `DELETE` wacht tot de index klaar is, bij miljoenen rijen minuten.
  `CONCURRENTLY` kan niet in een transactie, dus zo'n statement krijgt een eigen migratie
  met `-- flyway:executeInTransaction=false`. Faalt de bouw halverwege, dan blijft er een
  `INVALID` index achter die handmatig gedropt moet worden. `V3__notificatie_retentie.sql`
  doet het bewust zonder: die index is er vóór de eerste productiedata.
- **Houd de migratie en de entity gelijk.** In elk profiel staat
  `schema-management.strategy=validate`: wijkt een entity af van het gemigreerde
  schema, dan start de applicatie niet, en de testsuite dus ook niet.
- **In prod draait Flyway niet bij het starten**
  (`%prod.quarkus.flyway.migrate-at-start=false`). Op ZAD staat
  `QUARKUS_FLYWAY_MIGRATE_AT_START=true` in de deploymentconfig; zonder die
  instelling faalt de container op een lege database.
- Queries gebruiken het JPA-metamodel (`Poging_.NOTIFY_ID`) in plaats
  van losse strings. De `hibernate-processor` genereert dat; hij staat expliciet in
  `annotationProcessorPaths` omdat classpath-processors sinds JDK 23 niet meer
  vanzelf draaien.
- Schrijf kolomnamen in de migratie met underscores (`laatste_status_update`) en
  zet ze met `@Column(name = ...)` expliciet op de entity.

## Configuratie en secrets

Deze waarden staan leeg in `application.properties` en **laten de applicatie niet
starten als ze ontbreken**:

| Property | Gebruikt door |
|----------|---------------|
| `notify.api-key` | `NotifyNLVerzendAdapter`: JWT voor NotifyNL |
| `notify.callback.bearer-token` | `NotifyNLCallbackAuthFilter`: controle op de NotifyNL-callback |
| `hash.pepper` | `HashHelper`: HMAC-SHA-256 om BSN/KVK/RSIN en e-mailadressen te pseudonimiseren voor het logboek |
| `nmc.kek.huidige-versie` | `ConfigKekProvider`: KEK-versie waarmee `Sleutelbeheer` nieuwe sleutels per notificatie wrapt |
| `nmc.kek.versie.<n>` | `ConfigKekProvider`: KEK per versie, base64 van 32 bytes; die van de huidige versie is verplicht, oudere zolang er rijen met die `kek_versie` zijn |
| `nmc.webhook.jwt.private-key` | `WebhookSleutel`: RSA-sleutel (PKCS#8-PEM, minstens 2048 bits, kop en regelafbrekingen mogen weg) voor de JWT op de webhook |
| `nmc.webhook.jwt.key-id` | `WebhookSleutel`: `kid` in de JWT-header en in de JWKS |

Met een default, en dus alleen per omgeving te overschrijven als dat nodig is:
`nmc.dienstverlener.id` (de rij uit V8), `nmc.taak.*` (interval, batch, lease,
max-pogingen van de worker-lus) en `nmc.verzendbudget.*` (Notify-service, tokens per
minuut, het vaste aandeel van de navraag), `nmc.navraag.*` (momenten en bewaartermijn van
NotifyNL), `nmc.vaststelling.*` (termijn en callback-venster), `nmc.beleid.*` (geldigheid
en herverzend-wachttijd, per berichttype te overschrijven als
`nmc.beleid.<berichttype>.<sleutel>`) en `nmc.webhook.*` (interval,
bundel, max-mislukkingen, herpoging- en pauzewachttijd, time-out, `jwt.issuer`,
`jwt.geldigheid`). Onder `%test` staat de lus uit en is het budget klein, zodat tests
het uitputten.

Lokaal horen ze in een niet-ingecheckte `src/main/resources/application-dev.properties`,
nooit in `application.properties`. Onder `%test` staan dummywaarden.

Ook `quarkus.rest-client.profielservice.url` en `quarkus.rest-client.notify.url`
hebben buiten `%dev`/`%test` geen default en moeten per omgeving gezet worden.
Alle prod-config loopt via `QUARKUS_*`- en andere env-vars; de volledige lijst
staat in `docs/zad-deploy.md`.

Jackson is in `pom.xml` bewust boven de Quarkus-BOM getild (`jackson-bom` wordt
eerst geïmporteerd) vanwege twee databind-advisories. Haal die override pas weg
als de Quarkus-BOM zelf een veilige versie levert.

## Codestijl en patronen

- **Constructor-injectie, geen field-injectie.** Dit wijkt bewust af van andere
  repo's in de organisatie, ook van de profiel-service.
- **Repositories implementeren `PanacheRepositoryBase<T, Id>`**, niet de
  active-record-stijl met `PanacheEntity`, zodat ze via de constructor te injecteren
  zijn. Gebruik niet `PanacheRepository<T>`: die legt het id-type vast op `Long`,
  en `Notificatie` heeft een `UUID`, waardoor methodes als `deleteById` stil
  verkeerd gaan.
- **Foutafhandeling:** adapters vertalen een `WebApplicationException` van een
  externe dienst naar een eigen exception (`ProfielServiceException`,
  `NotifyNLVerzendException`, ...). Services gooien domeinexceptions. Alleen
  controllers en filters bouwen een HTTP-antwoord, via de `Problems`-helper
  (`HttpProblem`). Er is geen globale exception mapper; wat een controller niet
  afvangt wordt een 500.
- **Logboek:** een controllermethode met `@Logboek` moet de betrokkene zetten
  vóór de interceptor afrondt: `logboekContext.setDataSubjectId(...)` met een
  waarde uit `HashHelper`, nooit het ruwe BSN/KVK/RSIN of e-mailadres.
- **Webhook-URL's** gaan vóór elke levering door `CallbackUrlValidator` en
  `CallbackUrlValidator.normaliseer` (via `WebhookUrlControle`): de uitgaande
  REST-client vergelijkt het scheme hoofdlettergevoelig. De validator is een denylist
  op vorm en geen volledige SSRF-bescherming; zie de javadoc.

### Witregels rond `if`-statements

Gebruik witregels rondom `if`-statements voor de leesbaarheid: een lege regel
vóór het `if`-blok, ná het `if`-blok, en vóór een afsluitende `return`. Een `if`
dat het eerste statement van een methode of blok is heeft geen witregel ervóór
nodig.

De regel geldt voor `if`-statements met accolades. Een eenregelige guard clause
valt erbuiten: die hoort juist tegen de regel erboven aan te staan.

Niet alle bestaande code volgt dit al; pas het toe op code die je schrijft of
wijzigt.

```java
// Niet
String emailAdres = profielServiceAdapter.zoekEmailAdres(identificatie);
Notificatie notificatie = new Notificatie(callbackUrl);
if (berichtgegevens != null) {
    personalisation.putAll(berichtgegevens);
}
return notificatie;

// Wel
String emailAdres = profielServiceAdapter.zoekEmailAdres(identificatie);
Notificatie notificatie = new Notificatie(callbackUrl);

if (berichtgegevens != null) {
    personalisation.putAll(berichtgegevens);
}

return notificatie;
```

## Teststrategie

Beoordeel bij elke codewijziging of er tests bij of om moeten.

- **Happy én unhappy paths.** Foutgevallen, edge cases en validatiefouten horen
  erbij, niet alleen het successcenario.
- **Kies testdata die het gedrag uitlokt**, niet de makkelijkste die slaagt. Bij
  collecties minstens leeg, één en meerdere elementen: één e-mailadres in de
  Profielservice-respons verbergt of de scope-prioriteit werkt.
- **Controllertests** zijn `@QuarkusTest` met REST-assured tegen het echte pad.
  Mock externe diensten op clientniveau met `@InjectMock @RestClient` op de
  gegenereerde API (`ProfielApi`, `SendAMessageApi`) plus Mockito, niet over HTTP.
- **Unittests zonder Quarkus** voor losse logica (`CallbackUrlValidator`,
  `HashHelper`, adapters met gemockte afhankelijkheden). Ze tellen mee voor de
  coverage.
- **Fuzzing** overwegen bij input-parsing, validatielogica en security-gevoelige
  code; voeg dan een `@FuzzTest` of standalone target toe in `fuzzing/`.

## Commentaar

Houd commentaar compact. Eén of twee zinnen die zeggen wát er niet vanzelf
spreekt en waaróm. Geen alinea's met de afweging, de alternatieven en de
meetresultaten erbij; die horen in de commitmessage of het issue.

Schrijf alleen op wat je hebt gecontroleerd. Een verklaring van een mechanisme,
zoals "de REST-client doet X" of "de generator maakt Y", is een bewering over
gedrag: klopt hij niet, dan is hij schadelijker dan geen commentaar, want de
volgende lezer bouwt erop voort. Weet je het niet zeker, laat het weg of noteer
het als aanname.

```java
// Niet
// Persist (en flush) vóór de NotifyNL-aanroep, zodat een INSERT-fout (constraint, DB down,
// pool uitgeput) opduikt vóórdat de e-mail verstuurd is. Let op: flush is geen commit — dit
// dekt alleen faal vóór het versturen. Faalt de commit ná verstuurEmail(), dan rolt ook deze
// INSERT terug: e-mail verstuurd, geen record.

// Wel
// Flush vóór het versturen, zodat een INSERT-fout geen verstuurde e-mail zonder record
// oplevert. Een fout bij de commit daarna kan dat nog wel.
```

Verwijs in commentaar niet naar CLAUDE.md-secties. Beschrijf de regel zelf, zodat
het commentaar zonder dit bestand leesbaar blijft.

**Noem geen issuenummers in javadoc of commentaar.** Beschrijf het probleem zelf.
Een nummer verwijst naar een discussie die de lezer niet voor zich heeft, en zodra
het issue gesloten is voegt het niets meer toe. Een kaal `#732` is hier bovendien
misleidend: deze repo heeft een eigen nummerreeks, dus het wijst naar een PR in
deze repo in plaats van naar het issue. De bestaande `TODO #nnn`-commentaren wijken
hiervan af; herschrijf ze wanneer je die code toch aanraakt. In dít bestand mag
een verwijzing wel, maar alleen naar werk dat nog loopt.

## Git-werkwijze

- **Nooit direct pushen naar `main`.** Alles via een feature branch en een Pull
  Request.
- **Merge squash.** De commitmessage eindigt dan op `(#nummer)`. In oudere
  historie staan nog merge-commits; dat is niet het patroon om te volgen.
- Commitmessages zijn een korte Nederlandse beschrijving, eventueel met een
  conventional-commit-prefix (`fix:`, `test:`, `ci:`, `docs:`).
- Gebruik de PR-template in `.github/PULL_REQUEST_TEMPLATE.md`.
- Voeg bij het aanmaken van een PR **geen** reviewer toe.
- Draai `./mvnw verify` vóór je een PR opent; CI draait dezelfde gate als verplichte
  check (`Maven verify`), en een rode build blokkeert de merge.

### Issues en PR's koppelen

Issues staan in de
[MijnOverheidZakelijk](https://github.com/MinBZK/MijnOverheidZakelijk/issues)-tracker,
niet in deze repo. NMC-issues herken je aan het voorvoegsel `NMC:` in de titel; er
is geen apart NMC-label. Elke PR hoort bij een issue, en die koppeling moet van
twee kanten zichtbaar zijn:

- **Zet het issuenummer vooraan in de branchnaam**, gevolgd door een korte
  kebab-case-beschrijving: `1049-callback-url-verbindingsmoment`.
- **Noem het issue in de PR-beschrijving én de PR in het issue.** GitHub legt die
  koppeling niet vanzelf, omdat de issues in een andere repo staan.
- **Verwijs cross-repo altijd voluit**: `MinBZK/MijnOverheidZakelijk#1049`, nooit
  kaal `#1049`. Deze repo heeft een eigen nummerreeks voor issues en PR's (nu
  rond 45), dus een kale verwijzing wijst hier naar niets of naar de verkeerde PR.

## Belangrijke bestanden

| Pad | Beschrijving |
|-----|--------------|
| `src/main/resources/META-INF/openapi.yaml` | Servercontract voor de notificatie-endpoints: bron voor `/q/openapi` én de codegen |
| `src/main/resources/META-INF/notifynl-callback-openapi.yaml` | Servercontract voor de NotifyNL-callback |
| `src/main/resources/openapi/` | Clientcontracten voor Profielservice en NotifyNL |
| `src/main/resources/application.properties` | Alle configuratie, per profiel (`%dev`, `%test`, `%prod`), incl. JaCoCo-excludes |
| `src/main/resources/db/migration/` | Flyway-migraties |
| `pom.xml` | Quarkus-BOM, Jackson-override, generator-configuratie en de JaCoCo-gate, met toelichting per keuze |
| `docs/lokaal-testen.md` | Container-image lokaal bouwen en draaien met jib en Podman |
| `docs/zad-deploy.md` | ZAD PR-preview-deploys, benodigde env-vars en debugroutes |
| `.clusterfuzzlite/` | Build voor de standalone fuzz-targets |
| `.github/workflows/` | CI: Maven-build, CodeQL, Scorecard, ClusterFuzzLite, ZAD-deploy |

## Deploy

ZAD (project `nd-j7s`, component `nmcapi`) is de **PR-preview- en
ontwikkelomgeving**. Elke PR krijgt een `pr-<nummer>`-deployment die zijn
configuratie via `clone-from` erft van de `feature`-deployment; push naar `main`
gaat naar de persistente `stable`-deployment. Echte releases draaien op een ander
cluster.

De workflow bepaalt alleen wélk container-image draait, en deployt op digest.
Applicatieconfiguratie (datasource, NotifyNL-keys, pepper) staat in de deployment
zelf, in de ZAD Operations Manager, niet in de workflow en niet in deze repo.

Details en debugroutes: `docs/zad-deploy.md`.

## Referentierepo's (naast deze repo)

- `../moza-profiel-service`: Profielservice, Quarkus (`src/main/resources/META-INF/openapi.yaml`)
- `../moza-verificatie-service`: Quarkus; voorbeeld van de NotifyNL-integratie
- `../moza-portaal`: Next.js-portaal met `dependencies/omc/swagger.json`; zie de
  kanttekening bij de statussen.
