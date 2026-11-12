# Architectuur van de NMC

Hoe de NotificatieManagementComponent werkt en welke aannames er over de omliggende systemen
gelden. Het overzicht voor een eerste kennismaking (wat de NMC is, de flow, het domeinmodel,
de API) staat in de [`README`](../README.md); dit document gaat een laag dieper. De werkregels
voor wie er code in wijzigt staan in [`AGENTS.md`](../AGENTS.md) en `.claude/rules/`.

Het ontwerp van het statusmodel, het eventlog en de takentabel staat in
[ADR 0024: georkestreerde state machine met eventlog](https://github.com/MinBZK/MijnOverheidZakelijk/blob/main/Docs/structurizr/decisions/0024-georkestreerde-state-machine-met-eventlog.md)
in de MijnOverheidZakelijk-repo. Dit document beschrijft hoe de NMC dat ontwerp
uitvoert.

**Houd dit document bij.** Verandert het gedrag van een mechanisme dat hier beschreven
staat, werk dan de betreffende sectie in dezelfde PR bij.

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

### Endpoints

- `POST /api/nmc/v1/centraal/notificaties`: neemt een notificatie aan op een
  identificerend nummer en antwoordt 202; de verzendtaak haalt het
  e-mailadres later op bij de Profielservice.
- `POST /api/nmc/v1/decentraal/notificaties`: idem met een e-mailadres van de
  aanroeper; geen Profielservice-lookup.
- `POST /api/nmc/v1/notifynl-callback`: ontvangt de afleverstatus van NotifyNL,
  beveiligd met een bearer token (`NotifyNLCallbackAuthFilter`). `ReceiptOpslag`
  slaat de receipt op als `RECEIPT_VERWERKEN`-taak (uniek op poging, status en
  tijdstip) en de controller antwoordt 204, ook bij een receipt die bij geen poging
  hoort (geteld in `nmc.receipts.afgewezen`). `ReceiptTaakHandler` verwerkt hem.
- `GET /api/nmc/v1/notificaties/wijzigingen` en `PUT .../wijzigingen/bevestiging`: de
  eventfeed, zie hieronder.
- `GET /api/nmc/v1/.well-known/jwks.json`: de publieke sleutel van de webhook-JWT.

### Aanname

Beide intakes gaan via `AannameService`: notificatie op `aangenomen` met event 0, de
versleutelde payload en een verzendtaak in één transactie; het quotum per
dienstverlener per dag (`dienstverlener.quotum_per_dag`, zonder waarde daar
`nmc.aanname.quotum-per-dag`) geeft 429.
De controllers antwoorden 202 via `AannameResponse.notificatieAangenomen`; daarvoor genereert
de `notificaties`-run in de pom met `returnResponse=true`.

**Eén geconfigureerde dienstverlener.** `dienstverlener` heeft één rij uit de
migratie; `DvProvider` levert die `dv_id`, die op elke notificatie en elk event
staat. Tokenvalidatie per dienstverlener vervangt later alleen de provider.

**Ontvanger en personalisation staan versleuteld op de rij** (`Sleutelbeheer`, een
eigen sleutel per notificatie, gewrapt met de KEK): bij decentrale regie het
e-mailadres van de aanroeper, bij centrale regie het identificerend nummer, nooit het
adres uit de Profielservice.

### Statusmodel

`notificatie` draagt de status en een versie, `poging` de uitkomst per verzending,
`event` het eventlog met één rij per overgang. `Overgangsfunctie` is de enige schrijver
van de status; een deferred databasetrigger weigert een nieuwe versie zonder event.

`Overgangsfunctie` vergrendelt de `notificatie`-rij (`PESSIMISTIC_WRITE`), toetst de
overgang aan `Overgangsregels`, verhoogt de versie met precies één en schrijft het
event, in de transactie van de aanroeper. De deferred constraint trigger
`notificatie_overgang` (V5) bewaakt ook schrijvers buiten Hibernate, maar controleert
minder: een nieuwe rij begint op `AANGENOMEN`, de status verandert alleen met een nieuwe
versie, een nieuwe versie is precies één hoger, en heeft in dezelfde transactie een event
met die versie als volgnummer. Welke overgang toegestaan is en de vergrendeling dwingt hij
niet af.

`NotificatieStatus` (tien waarden, levenscyclus) en `PogingStatus` (zeven waarden,
uitkomst per verzending) staan als `CHECK`-constraint in V4. In de API en het CloudEvent
gaan statussen en redenen als kebab-case (`toApiValue`).

Het eventlog `event` is ook het audittrail: één rij per overgang, met het volgnummer gelijk
aan de versie van de notificatie.

### Takentabel en worker-lus

`taak` (per soort gepartitioneerd) draagt de bijwerkingen met `due` als timer;
`TaakClaimer` claimt per ronde met `PESSIMISTIC_WRITE` en de skip-locked-hint
(`FOR NO KEY UPDATE SKIP LOCKED`), verdeeld over de dienstverleners met werk en voor het
verzenden en de navraag binnen het `verzendbudget` per Notify-service per minuut.
`TaakWorker` draait per soort een `@Scheduled`-ronde en geeft elke taak aan de
`TaakHandler` van die soort. Handlers: `VerzendTaakHandler`, `ReceiptTaakHandler`,
`NotifyNLNavraagTaakHandler`, `BezorgingVaststellenTaakHandler`, `ControleTaakHandler`,
`TerugkoppelTaakHandler`, `WisTaakHandler` en `OnderhoudTaakHandler`; voor
`ONGELDIG_MELDEN` is er nog geen handler.

`ControleTaakHandler` plant zichzelf opnieuw, geeft een notificatie zonder taak de taak
die bij haar status hoort en plant een terugkoppeltaak voor elke dienstverlener met een
webhook die er nog geen heeft.

### Verzenden

`VerzendTaakHandler` doet in een eerste transactie `aangenomen` naar `in-verzending` met
een geplande poging, roept daarbuiten de Profielservice (bij centrale regie) en NotifyNL
aan met het poging-id als `reference`, en doet in een tweede transactie `in-verzending`
naar `verzonden` met een navraagtaak. Een herclaim zoekt eerst op `reference`.

Foutafhandeling:

- Een `ValidationError` van NotifyNL op `email_address` en een partij zonder adres leiden
  tot een eindstatus (`niet-bezorgbaar`).
- Een andere 4xx, behalve 401, 403 en 429, ligt aan het verzoek van het NMC en kost een
  poging.
- 401, 403, 429, 5xx, een verbindingsfout, een ongeldige NotifyNL-configuratie en een
  ontbrekende KEK-versie stellen de taak uit zonder poging, begrensd door `geldig_tot`.

De verzendtaak schrijft nog geen LDV-registratie: de `@Logboek`-interceptor werkt alleen
binnen een REST-aanroep.

**Adresselectie in de Profielservice-respons** (`ProfielServiceAdapter`): eerst
een e-mailadres met exact de scope Dienstverlener + dienst, dan een met alleen
de Dienstverlener als scope, dan het default-adres, dan een adres zonder scopes.

NotifyNL krijgt per aanroep een vers JWT: `NotifyNLVerzendAdapter` bouwt het met
`NotifyNLJwtFactory` en zet het in `NotifyNLAuthorizationHolder`, waarna
`NotifyNLCredentialsProvider` (een `@Alternative` op de standaard
credentialsprovider van de extensie) het als bearer token meegeeft.

### Herverzending en `geldig_tot`

Een notificatie krijgt `Verzendbeleid.maxHerverzendingen` herverzendingen (standaard
één): een `temporary-failure` of `technical-failure` op een poging daarbinnen plant een
verzendtaak na `Verzendbeleid.herverzendWachttijd`, en de claim daarvan maakt de volgende
poging aan (`verzonden` naar `verzonden`, met event). Op de laatste toegestane poging leidt
een fout tot een eindstatus.

`geldig_tot` (aanname plus `Verzendbeleid.geldigheid` van het berichttype) begrenst het
starten van een poging: daarna eindigt de notificatie in `verlopen`, ook na een uitstel;
een poging die al bij NotifyNL ligt loopt door.

`ONGELDIG_MELDEN` is de taaksoort waarmee de NMC bij centrale regie een e-mailadres bij de
Profielservice ongeldig laat verklaren (fase 3). Dat volgt op een `permanent-failure`: het
adres bestaat niet of weigert definitief, dus het hoort niet opnieuw gekozen te worden. Een
tijdelijke fout op de laatste toegestane poging eindigt ook in `niet-bezorgbaar` met reden
`onbereikbaar`, maar zegt niets over het adres en meldt het niet ongeldig. Bij decentrale
regie werkt de OMC zijn eigen DCProfiel bij. De taak wordt nog niet gepland: het contract van
de Profielservice heeft daar nog geen operatie voor.

### Receipts, navraag en vaststellen

Afleverstatussen komen at-least-once en ongeordend binnen. NotifyNL biedt een callback
opnieuw aan bij elke niet-2xx; de NMC antwoordt 2xx zodra de receipt is opgeslagen, en een
fout in de verwerking laat de taak staan.

`ReceiptVerwerker` zoekt de poging op de `reference` uit de receipt (het poging-id) en
anders op het NotifyNL-id. Heeft de poging nog geen NotifyNL-id, dan is de receipt er eerder
dan de verzend-commit: de poging neemt het id over en de verwerker doet de overgang naar
`verzonden` namens de worker, ook bij een tussenstatus. Een ander NotifyNL-id dan dat op de
poging komt van een tweede verzending na een herclaim en wordt als duplicaat op dezelfde
poging vastgelegd.

De verwerker ordent per poging op het tijdstip in de receipt (`completed_at`, met `sent_at`
en `created_at` als terugval; zonder tijdstip geldt het moment van ontvangst en gaat de
receipt nooit vóór een al vastgelegde uitkomst); een herhaalde of oudere receipt verandert
niets. De poging wordt pas na het vergrendelen van de notificatie opnieuw gelezen, zodat een
receipt die op de vergrendeling wachtte de uitkomst van de eerste ziet. Een faalreceipt voor
een poging die niet meer de laatste is, geeft geen overgang. Een tussenstatus verandert
niets; een onbekende status komt op `poging.laatste_notify_status`, wordt op ERROR gelogd en
verandert verder niets.

**Receipttijd en registratietijd zijn gescheiden.** Het tijdstip uit de receipt staat op
`poging.receipt_tijdstip` en is het tijdstip voor het afleverbewijs; `event.geregistreerd`
en `notificatie.eindstatus_op` staan op de eigen klok.

**Navraag.** De verzend-commit plant per poging een navraagtaak volgens
`NotifyNLNavraagmomenten` (`nmc.navraag.*`); `NotifyNLNavraagTaakHandler` vraagt de status
op bij NotifyNL en geeft een uitkomst aan `ReceiptVerwerker`. Een 404 of het einde van de
bewaartermijn van NotifyNL zet de poging op `ONBEKEND` en de notificatie op
`bezorgstatus-onbekend`. Een verwerkte receipt rondt de navraag af.

**Vaststellen.** `delivered` plant een vaststeltaak op het tijdstip uit de receipt plus
`Vaststellingstermijn` (`nmc.vaststelling.*`); een faalreceipt binnen de termijn rondt die
af, een faalreceipt met een tijdstip daarna wordt alleen op de poging vastgelegd. De termijn
loopt vanaf `poging.bezorgd_op`, dat een latere faalreceipt niet overschrijft.

### Eventfeed

`GET /api/nmc/v1/notificaties/wijzigingen` leest het eventlog van de dienstverlener op
(transactie-id, event-id) onder het watermerk `pg_snapshot_xmin(pg_current_snapshot())`,
zodat een cursor nooit een event overslaat dat later committet dan een event met een hogere
transactie-id. De cursor is base64url van `epoch:transactieId:eventId`; een ander epoch
(`nmc.feed.cluster-epoch`), een positie onder het oudste event of boven de jongste
transactie (`pg_snapshot_xmax`) geeft 410. `PUT .../wijzigingen/bevestiging` schrijft
`bevestiging`, alleen vooruit.

Het opruimen van het eventlog spaart alleen partities waar een bevestiging of leverpositie in
wijst; een nog niet bevestigde cursor kan dus vervallen en krijgt bij de volgende aanroep
410. Dat is bewust niet afgedekt. De aanroeplimiet (`Aanroeplimiet`) telt per pod en geeft
bij 429 een `Retry-After`.

### Webhook

Heeft een dienstverlener een `webhook_url` in het register, dan levert één
`TERUGKOPPELEN`-taak per dienstverlener (`TerugkoppelTaakHandler`) de events na
`webhookpositie` onder het watermerk van de feed als CloudEvents-batch aan die URL
(`WebhookDispatcher`, `WebhookAdapter`), met de header `Nmc-Cursor` en een
RS256-bearer-JWT met `body_sha256` (`WebhookSleutel`). Er is geen push per overgang: de
webhook leest het eventlog.

De taak (`notificatie_id` leeg) plant zichzelf steeds opnieuw. De eerste plant
`ControleTaakHandler`, dus een nieuwe `webhook_url` gaat binnen `nmc.controle.interval`
leveren; een unieke index houdt het op één taak per dienstverlener, zodat met de claim nooit
twee pods tegelijk aan dezelfde dienstverlener leveren.

Per ronde:

1. Lees in een transactie register, positie en events (`Eventfeed.leesVoorWebhook`,
   `webhook_bundel` of anders `nmc.webhook.bundel` per aanroep, ten hoogste
   `nmc.feed.max-pagina`). Een positie uit een ander epoch begint bij het oudste event;
   zonder positie legt de ronde eerst de kop van het log vast (`Eventfeed.kopVanHetLog`),
   zodat alleen events van na de registratie gaan.
2. Verleng de lease en POST buiten de transactie.
3. Zet in één transactie met `TaakClaimer.stelUit` (epoch-getoetst) de positie vooruit of de
   mislukking vast.

Een mislukte levering is een externe fout en kost de taak geen poging. Wachttijd:
`nmc.webhook.herpoging-wachttijd` verdubbeld per mislukking; vanaf
`webhook_max_mislukkingen` (default `nmc.webhook.max-mislukkingen`) is de webhook
gepauzeerd met `nmc.webhook.pauze-wachttijd` verdubbeld per verdere mislukking, tot ten
hoogste een dag. Een ongeldige URL (`WebhookUrlValidator`) pauzeert direct, zonder aanroep,
met een ERROR. Een `2xx` is geen bevestiging; die blijft de feedcursor. Zonder `webhook_url`
rondt de taak af en gaat de leverpositie weg.

**Webhook en feed leveren hetzelfde CloudEvent.** `NotificatieStatusEvent.van` bouwt beide;
`id` is `event.uuid`, zodat een Dienstverlener een event uit de webhook in de feed herkent.
De identity-id is daar ongeschikt voor: die reeks loopt na een herstel uit back-up terug.

### Wissen en onderhoud

Wissen is een wistaak, verwijderen een onderhoudstaak.

`Notificatie.pasOvergangToe` zet `eindstatus_op` bij een eindstatus en maakt het leeg
daarbuiten. `Overgangsfunctie` plant bij elke overgang naar een eindstatus de `WISSEN`-taak
op `eindstatus_op` plus `nmc.wissen.termijn` (een upsert op de unieke index voor open taken,
dus een latere overgang naar een eindstatus verzet hem) en verwijdert hem bij een overgang
terug naar een status zonder eindstatus (`bezorgstatus-onbekend` naar `bezorgd`).
`WisTaakHandler` vergrendelt de notificatie, toetst status en `eindstatus_op` opnieuw en wist
de gewrapte sleutel met native SQL in `NotificatieRepository`, zonder versie en event.

`OnderhoudTaakHandler` is een taak per systeem (eerste rij uit V16) die zichzelf opnieuw
plant. Hij maakt eventpartities aan en ruimt ze op, herwrapt sleutels na een KEK-rotatie en
verwijdert notificaties na `nmc.afleverbewijs.bewaartermijn`. Elke stap loopt in batches van
`nmc.onderhoud.batch` met een eigen transactie, en een stap die faalt houdt de andere niet op.

### Partities van het eventlog

Het eventlog heeft bereikpartities op `transactie_id`. V16 maakte van de oude
default-partitie `event_0` (tot de eerstvolgende transactie-id plus de Flyway-placeholder
`event_marge`) en een nieuwe, lege `event_standaard`.

`EventlogPartitiebeheer` maakt `event_<van>` aan zodra de lopende partitie voor 80% gevuld
is, als losse tabel die met `ATTACH PARTITION` wordt aangehangen: dat houdt het eventlog niet
tegen terwijl PostgreSQL de default-partitie doorleest, en krijgt daarvoor in zijn eigen
transactie vijf minuten in plaats van de timeouts van de rol. Een nieuw bereik begint altijd
boven de al uitgedeelde transactie-ids: PostgreSQL weigert een bereik waarvoor al rijen in
de default-partitie staan. Liep het onderhoud achter, dan begint het nieuwe bereik
`nmc.onderhoud.partitie-marge` boven de volgende transactie-id en komen de events ertussen
in de default-partitie; dat wordt als ERROR gelogd, en die events worden per rij opgeruimd,
maar pas als er geen oudere bereikpartitie meer staat.

Een partitie gaat weg als ze onder het watermerk ligt, haar jongste event (op
transactie-id) ouder is dan de bewaartermijn en geen bevestiging of leverpositie uit het
huidige epoch, jonger dan de maximale cursorleeftijd, erin of eronder wijst. De leeftijd van
een leverpositie is `webhookpositie.geleverd_op`, niet `bijgewerkt_op`, dat ook bij een
mislukte levering verschuift. Het verwijderen toetst opnieuw onder
`LOCK TABLE event IN ACCESS EXCLUSIVE MODE` met `nmc.onderhoud.lock-timeout`. De
partitienaam komt in DDL; `EventPartitie` laat alleen `event_<getal>` toe.

### Nog niet gebouwd

- een endpoint om de status van één notificatie op te vragen;
- contactherstel, en dus ook de keuze tussen herverzending en contactherstel. Laat daar
  een uitbreidingspunt voor open en bouw het niet zonder afstemming;
- de handler voor `ONGELDIG_MELDEN` (wacht op een operatie in het Profielservice-contract);
- de Templating Service-koppeling: templates staan als vaste NotifyNL-template-IDs in
  `BerichtType`. Een eenvoudige mock met een vaste template volstaat, lage prioriteit;
- de OMC-koppeling en de orkestratie die de fasen aan elkaar knoopt;
- het observability-koppelvlak (verwerkings- en statusinformatie voor Dienstafnemers,
  waarschijnlijk voor de wMEBV-bewijslast). Het eventlog is daar al het audittrail voor.

## Technische stack

- **Runtime:** Quarkus 3.40.1, Java 25; één Maven-module met wrapper `./mvnw`
- **API:** contract-first uit `META-INF/openapi.yaml` en
  `META-INF/notifynl-callback-openapi.yaml`, Quarkus REST + Jackson
- **Uitgaande clients:** quarkus-openapi-generator voor Profielservice en NotifyNL; één
  REST-client voor de webhooks van de dienstverleners, met de URL per aanroep (`@Url`)
- **Persistentie:** PostgreSQL 18 + Hibernate ORM Panache + Flyway; in tests een embedded
  PostgreSQL (Zonky)
- **Test:** JUnit 5, REST-assured, Mockito, Jazzer (fuzzing)
- **Fouten:** RFC 9457 `application/problem+json` via quarkus-http-problem
- **Audit:** LDV-wrapper (`logboekdataverwerking`), standaard uitgeschakeld
- **Image:** jib, `eclipse-temurin:25-jre`, draait als uid 1001
- **Health:** smallrye-health op `/q/health`; readiness bevat een datasource-check

## Integratie-aannames

- **Versturen bij NotifyNL is synchroon, de afleverstatus asynchroon.** Het
  verzoek is een synchrone POST (JWT gebouwd uit de API-key) die alleen bevestigt
  dat NotifyNL het bericht heeft aangenomen, niet dat het bezorgd is. De echte
  uitkomst komt later via de callback. Die callback-URL en het bijbehorende bearer
  token stel je eenmalig per service/API-key in NotifyNL's dashboard in ("API
  integration" → "Callbacks"); ze zijn geen veld op het verzendverzoek. Het token
  moet gelijk zijn aan `notify.callback.bearer-token`.
- **Het contract van de Profielservice wordt niet automatisch gesynchroniseerd.**
  `src/main/resources/openapi/profielservice_api.json` is een kopie; wijzigt het
  contract in `../moza-profiel-service`, werk de kopie dan bij.

## Configuratie met een default

Deze waarden hebben een default in `application.properties` en worden alleen per
omgeving overschreven als dat nodig is. De verplichte waarden en secrets staan in
`AGENTS.md`; de volledige lijst env-vars voor ZAD in [`zad-deploy.md`](zad-deploy.md).

| Prefix | Waarvoor |
|--------|----------|
| `nmc.dienstverlener.id` | de rij uit V8 |
| `nmc.taak.*` | interval, batch, lease en max-pogingen van de worker-lus, en het uitstel na een storing |
| `nmc.navraag.*` | momenten en bewaartermijn van NotifyNL |
| `nmc.vaststelling.*` | termijn en callback-venster |
| `nmc.beleid.*` | geldigheid, herverzend-wachttijd en max-herverzendingen; per berichttype te overschrijven als `nmc.beleid.<berichttype>.<sleutel>` |
| `nmc.controle.*` | interval en maximum per ronde van de controletaak |
| `nmc.aanname.quotum-per-dag` | quotum voor een dienstverlener zonder eigen quotum in het register; leeg is onbeperkt |
| `nmc.verzendbudget.*` | Notify-service, tokens per minuut, de reservering van het verzenden en van de navraag |
| `nmc.feed.*` | grootste pagina, aanroepen per minuut, cluster-epoch, max-cursorleeftijd |
| `nmc.webhook.*` | interval, bundel, max-mislukkingen, herpoging- en pauzewachttijd, time-out, `jwt.issuer`, `jwt.geldigheid` |
| `nmc.wissen.termijn` | termijn tot het wissen van de sleutel na een eindstatus |
| `nmc.afleverbewijs.bewaartermijn` | wettelijk nog niet vastgesteld, dus per omgeving |
| `nmc.onderhoud.*` | interval, batch, max-batches, partitie-omvang, partitie-marge, lock-timeout |

Onder `%test` staat de lus uit, is het budget klein zodat tests het uitputten, zijn batch
en partities klein, en is KEK-versie 2 de huidige, zodat de onderhoudstaak sleutels onder
versie 1 kan herwrappen.

## Deploy

ZAD (project `nd-j7s`, component `nmcapi`) is de **PR-preview- en
ontwikkelomgeving**. Elke PR krijgt een `pr-<nummer>`-deployment die zijn
configuratie via `clone-from` erft van de `feature`-deployment; push naar `main`
gaat naar de persistente `stable`-deployment. Echte releases draaien op een ander
cluster.

De workflow bepaalt alleen wélk container-image draait, en deployt op digest.
Applicatieconfiguratie (datasource, NotifyNL-keys, pepper) staat in de deployment
zelf, in de ZAD Operations Manager, niet in de workflow en niet in deze repo.
Details en debugroutes: [`zad-deploy.md`](zad-deploy.md).
