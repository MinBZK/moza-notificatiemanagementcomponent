# Architectuur van de NMC

Hoe de NotificatieManagementComponent werkt en welke aannames er over de omliggende systemen
gelden. Het overzicht voor een eerste kennismaking (wat de NMC is, de flow, het domeinmodel,
de API) staat in de [`README`](../README.md); dit document gaat een laag dieper. De werkregels
voor wie er code in wijzigt staan in [`AGENTS.md`](../AGENTS.md) en `.claude/rules/`.

Het ontwerp van het statusmodel, het eventlog en de takentabel staat in
[ADR 0024: georkestreerde state machine met eventlog](https://github.com/MinBZK/MijnOverheidZakelijk/blob/main/Docs/structurizr/decisions/0024-georkestreerde-state-machine-met-eventlog.md)
in de MijnOverheidZakelijk-repo. Dit document beschrijft hoe de NMC dat ontwerp
uitvoert.

Dit document geeft het overzicht. Hoe een mechanisme in detail werkt (foutafhandeling,
wachttijden, cursorformaat, partitiegrenzen) staat in de javadoc van de klasse die hier
genoemd wordt. **Houd beide bij.** Verandert het gedrag van een mechanisme, werk dan de
javadoc bij en, als het overzicht hier niet meer klopt, de betreffende sectie.

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

Voor de soorten berichten volgt de NMC de
[begrippen rond berichten van MOZa](https://mijnoverheidzakelijk.nl/onderwerpen/berichten/begrippen/)
(als markdown: dezelfde URL met `index.md` erachter). Die hebben nog geen officiële status. In
die termen:

- de e-mail die de NMC verstuurt is een **kennisgeving (melding)** of een **attendering**;
- de statusupdate aan de Dienstverlener via de eventfeed en de webhook is een
  **gebeurtenisbericht**;
- het bericht langs een andere weg na een mislukte bezorging is een **contactherstelmelding**
  (Awb 2:11).

Begrippen van de NMC zelf:

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
- `GET /api/nmc/v1/notificaties/events` en `PUT .../events/bevestiging`: de
  eventfeed, zie hieronder.
- `GET /api/nmc/v1/.well-known/jwks.json`: de publieke sleutel van de webhook-JWT, en tijdens
  een sleutelwissel een tweede.
- `POST /api/nmc/v1/contactherstellen` en `GET` en `PATCH .../contactherstellen/{contactherstelId}`:
  alleen het contract en `ContactherstelController`. Elke operatie antwoordt 501
  (`Problems.notImplemented`), na de bodyvalidatie uit het contract. Alle drie hebben `@Logboek`:
  het starten met de gehashte ontvanger als betrokkene, de andere twee voorlopig met de
  dienstverlener.

### Aanname

Beide intakes gaan via `AannameService`: notificatie op `aangenomen` met event 0, de
versleutelde payload en een verzendtaak in één transactie, met een quotum per dienstverlener
per dag. De aanname roept de Profielservice en NotifyNL niet aan.

**Herhaalde aanvraag.** Met een `dvRef` in de aanvraag is de aanname idempotent op
dienstverlener, referentie en inhoud: een herhaling geeft 202 met het bestaande id, maakt niets
aan en telt niet voor het quotum. De referentie staat niet leesbaar op de rij:
`notificatie.dv_ref_hmac` is een HMAC over dienstverlener en referentie en `payload_hash` een
HMAC over de inhoud, elk met een eigen sleutel uit `hash.pepper` (`HashHelper.hmac`), met de
pepperversie erbij. Twee gelijktijdige aanvragen met dezelfde referentie gaan na elkaar door
een `pg_advisory_xact_lock`; de unieke index `notificatie_dv_ref_idx` is het vangnet. De
herkenning geldt zolang de rij bestaat. Dezelfde referentie met een andere inhoud is een nieuwe
notificatie. Een andere `hash.pepper` geeft andere HMAC's: een herhaling van een aanvraag van
vóór die wijziging wordt dan niet herkend. `pepper_versie` staat op de rij voor een latere
rotatie; er is nu één versie.

**Grenzen op de aanvraag.** Het identificerend nummer is 8 of 9 cijfers; `dienstverlener` en
`dienst` bevatten een leesbaar teken en geen regeleinde. De berichtgegevens zijn ten hoogste 50
variabelen, met namen tot 100 en waarden tot 2000 tekens (`BerichtgegevensConstraintValidator`).
Een body boven `quarkus.http.limits.max-body-size` (1 MB) krijgt 413 voordat hij is ingelezen;
die grens geldt voor elk endpoint.

**Eén geconfigureerde dienstverlener.** `dienstverlener` heeft één rij uit de
migratie; `DvProvider` levert die `dv_id`, die op elke notificatie en elk event
staat. Tokenvalidatie per dienstverlener vervangt later alleen de provider.

**Ontvanger en personalisation staan versleuteld op de rij** (`Sleutelbeheer`, een
eigen sleutel per notificatie, gewrapt met de KEK): bij decentrale regie het
e-mailadres van de aanroeper, bij centrale regie het identificerend nummer, nooit het
adres uit de Profielservice.

### Statusmodel en eventlog

`notificatie` draagt de status en een versie, `poging` de uitkomst per verzending,
`event` het eventlog met één rij per overgang en het volgnummer gelijk aan de versie. Het
eventlog is ook het audittrail.

`Overgangsfunctie` is de enige schrijver van de status. De deferred constraint trigger
`notificatie_overgang` (V5) bewaakt ook schrijvers buiten Hibernate, maar controleert minder:
een nieuwe rij begint op `AANGENOMEN`, de status verandert alleen met een nieuwe versie, die
precies één hoger is en in dezelfde transactie een event met die versie als volgnummer en
dezelfde status en reden heeft. Welke overgang toegestaan is en de vergrendeling dwingt hij
niet af.

`NotificatieStatus` (levenscyclus) en `PogingStatus` (uitkomst per verzending) staan als
`CHECK`-constraint in V4. In de API en het CloudEvent gaan statussen en redenen als
kebab-case (`toApiValue`).

### Takentabel en worker-lus

Elke bijwerking is een rij in `taak` (per soort gepartitioneerd) met `due` als timer.
`TaakWorker` draait per soort een ronde, `TaakClaimer` claimt de taken en verdeelt ze over de
dienstverleners en het verzendbudget, en de `TaakHandler` van die soort voert ze uit:

| Taaksoort | Handler | Wat hij doet |
|-----------|---------|--------------|
| `VERZENDEN` | `VerzendTaakHandler` | verzending en herverzending |
| `RECEIPT_VERWERKEN` | `ReceiptTaakHandler` | een opgeslagen afleverstatus verwerken |
| `RECONCILIEREN` | `NotifyNLNavraagTaakHandler` | de afleverstatus navragen als de receipt uitblijft |
| `BEZORGING_VASTSTELLEN` | `BezorgingVaststellenTaakHandler` | `bezorgd` na de vaststellingstermijn definitief maken |
| `TERUGKOPPELEN` | `TerugkoppelTaakHandler` | events aan de webhook leveren |
| `WISSEN` | `WisTaakHandler` | de sleutel wissen na een eindstatus |
| `CONTROLE` | `ControleTaakHandler` | vangnet voor notificaties en webhooks zonder taak |
| `ONDERHOUD` | `OnderhoudTaakHandler` | partities, KEK-rotatie en bewaartermijn |
| `ONGELDIG_MELDEN` | nog geen | zie hieronder |

### Verzenden en herverzenden

`VerzendTaakHandler` zet een notificatie in een eerste transactie op `in-verzending` met een
geplande poging, roept daarbuiten de Profielservice (bij centrale regie) en NotifyNL aan met het
poging-id als `reference`, en zet haar in een tweede transactie op `verzonden` met een
navraagtaak. Een fout over het adres of de partij geeft een eindstatus; een fout in het verzoek
van de NMC kost een poging; een storing stelt uit zonder poging. Welke fout waar valt, staat in
de javadoc van `VerzendTaakHandler`; welk adres uit de Profielservice gekozen wordt in die van
`ProfielServiceAdapter`.

De verzendtaak schrijft nog geen LDV-registratie: de `@Logboek`-interceptor werkt alleen
binnen een REST-aanroep.

Een notificatie krijgt `Verzendbeleid.maxHerverzendingen` herverzendingen: een tijdelijke of
technische fout plant zolang er nog een over is een nieuwe verzendtaak, en op de laatste
toegestane poging leidt een fout tot een eindstatus. `geldig_tot` (aanname plus
`Verzendbeleid.geldigheid` van het berichttype) begrenst het starten van een poging: daarna
eindigt de notificatie in `verlopen`; een poging die al bij NotifyNL ligt loopt door.

`ONGELDIG_MELDEN` is de taaksoort waarmee de NMC bij centrale regie een e-mailadres bij de
Profielservice ongeldig laat verklaren (fase 3). Dat volgt op een `permanent-failure`: het
adres bestaat niet of weigert definitief, dus het hoort niet opnieuw gekozen te worden. Een
tijdelijke fout op de laatste toegestane poging eindigt ook in `niet-bezorgbaar` met reden
`onbereikbaar`, maar zegt niets over het adres en meldt het niet ongeldig. Bij decentrale
regie werkt de OMC zijn eigen DCProfiel bij. De taak wordt nog niet gepland: het contract van
de Profielservice heeft daar nog geen operatie voor.

### Receipts, navraag en vaststellen

Afleverstatussen komen at-least-once en ongeordend binnen. NotifyNL biedt een callback
opnieuw aan bij elke niet-2xx; de NMC antwoordt 2xx zodra `ReceiptOpslag` de receipt als taak
heeft opgeslagen, en een fout in de verwerking laat de taak staan. `ReceiptVerwerker` legt de
uitkomst op de poging vast en ordent op het tijdstip in de receipt, zodat een herhaalde of
oudere receipt niets verandert.

**Receipttijd en registratietijd zijn gescheiden.** Het tijdstip uit de receipt staat op
`poging.receipt_tijdstip` en is het tijdstip voor het afleverbewijs; `event.geregistreerd`
en `notificatie.eindstatus_op` staan op de eigen klok.

**Navraag.** Blijft de receipt uit, dan vraagt `NotifyNLNavraagTaakHandler` de status op
volgens `NotifyNLNavraagmomenten`. Kent NotifyNL de verzending niet meer, dan wordt de
notificatie `bezorgstatus-onbekend`.

**Vaststellen.** Een `delivered` kan binnen de `Vaststellingstermijn` nog door een faalreceipt
herroepen worden; daarna maakt `BezorgingVaststellenTaakHandler` de notificatie
`definitief-bezorgd`.

### Eventfeed en webhook

De Dienstverlener hoort de uitkomst via twee wegen die allebei het eventlog lezen; er is geen
push per overgang.

- **Eventfeed** (`Eventfeed`): de Dienstverlener haalt de events op met een cursor en schrijft
  die terug als bevestiging. De feed leest onder een watermerk, zodat een cursor nooit een
  event overslaat dat later committet. Een vervallen cursor geeft 410. De aanroeplimiet
  (`Aanroeplimiet`) telt per pod.
- **Webhook** (`TerugkoppelTaakHandler`, `WebhookDispatcher`): heeft een dienstverlener een
  `webhook_url`, dan levert één terugkoppeltaak per dienstverlener de events na zijn
  leverpositie als CloudEvents-batch aan die URL, met een ondertekende JWT. Een `2xx` is geen
  bevestiging; die blijft de feedcursor. Een mislukte levering wacht steeds langer en pauzeert
  de webhook uiteindelijk.

**De JWKS en een sleutelwissel.** Het antwoord van de JWKS draagt
`Cache-Control: public, max-age=` met `nmc.webhook.jwt.jwks-max-age`, zodat een ontvanger weet
hoe lang hij het mag bewaren. Naast de eigen sleutel publiceert de JWKS de publieke sleutel uit
`nmc.webhook.jwt.extra-public-key` met de id uit `nmc.webhook.jwt.extra-key-id`; het NMC
ondertekent daar niet mee. Een wissel gaat daarmee in drie uitrollen:

1. zet de nieuwe publieke sleutel als extra sleutel en wacht, nadat de uitrol klaar is, minstens
   de `max-age`, zodat elke ontvanger hem kent. Het gaat om de `max-age` die tot dan toe gold: een
   lagere waarde in dezelfde uitrol verkort het wachten niet;
2. maak de nieuwe sleutel de eigen sleutel (`private-key` en `key-id`) en zet de oude publieke
   sleutel als extra sleutel, voor JWT's die nog onderweg zijn;
3. haal de extra sleutel weg, minstens `jwt.geldigheid` nadat de uitrol van stap 2 klaar is.

Alleen een sleutel of alleen een id, een id gelijk aan die van de eigen sleutel, of dezelfde
sleutel als de eigen sleutel laat de applicatie niet starten.

**Webhook en feed leveren hetzelfde CloudEvent.** `NotificatieStatusEvent` bouwt beide;
`id` is `event.cloudevent_id`, zodat een Dienstverlener een event uit de webhook in de feed herkent.
De identity-id is daar ongeschikt voor: die reeks loopt na een herstel uit back-up terug.

### Wissen, onderhoud en partities

Wissen is een wistaak, verwijderen een onderhoudstaak.

- Elke overgang naar een eindstatus plant via `Overgangsfunctie` een `WISSEN`-taak op
  `eindstatus_op` plus `nmc.wissen.termijn`. `WisTaakHandler` wist dan de gewrapte sleutel met
  native SQL, zonder versie en event; daarna is de rij pseudoniem.
- `OnderhoudTaakHandler` is een taak per systeem (eerste rij uit V15) die partities van het
  eventlog aanmaakt en opruimt, sleutels herwrapt na een KEK-rotatie en notificaties verwijdert
  na `nmc.afleverbewijs.bewaartermijn`.
- Het eventlog heeft bereikpartities op `transactie_id` (`EventlogPartitiebeheer`). Een
  partitie gaat pas weg als geen geldige bevestiging of leverpositie erin wijst; een nog niet
  bevestigde cursor kan daardoor vervallen.

### Nog niet gebouwd

- een endpoint om de status van één notificatie op te vragen;
- contactherstel (alleen het contract staat er), en dus ook de keuze tussen herverzending en
  contactherstel. Laat daar
  een uitbreidingspunt voor open en bouw het niet zonder afstemming;
- de handler voor `ONGELDIG_MELDEN` (wacht op een operatie in het Profielservice-contract);
- de Templating Service-koppeling: templates staan als vaste NotifyNL-template-IDs in
  `BerichtType`. Een eenvoudige mock met een vaste template volstaat, lage prioriteit;
- de OMC-koppeling en de orkestratie die de fasen aan elkaar knoopt;
- het observability-koppelvlak (verwerkings- en statusinformatie voor Dienstafnemers,
  waarschijnlijk voor de wMEBV-bewijslast). Het eventlog is daar al het audittrail voor.

## Technische stack

- **Runtime:** Quarkus 3.40.1, Java 25; één Maven-module met wrapper `./mvnw`
- **API:** contract-first uit `META-INF/openapi.yaml`, `META-INF/contactherstel-openapi.json`
  en `openapi-callback/notifynl-callback-openapi.yaml`, Quarkus REST + Jackson
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
| `nmc.verzendbudget.*` | tokens per minuut, de reservering van het verzenden en van de navraag |
| `nmc.feed.*` | grootste pagina, aanroepen per minuut, cluster-epoch, max-cursorleeftijd |
| `nmc.webhook.*` | interval, bundel, max-mislukkingen, herpoging- en pauzewachttijd, time-out, `jwt.issuer`, `jwt.geldigheid`, `jwt.jwks-max-age`; `jwt.extra-public-key` en `jwt.extra-key-id` zijn leeg buiten een sleutelwissel |
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
gaat naar de persistente `stable`-deployment. Dezelfde push deployt ook `stable` in
het tst-project `nt-8bl` (NMC - tst); de `pentest`-deployment daar wordt nooit
automatisch bijgewerkt, alleen met de hand via `deploy-pentest.yml`. Echte
releases draaien op een ander cluster.

De workflow bepaalt alleen wélk container-image draait, en deployt op digest.
Applicatieconfiguratie (datasource, NotifyNL-keys, pepper) staat in de deployment
zelf, in de ZAD Operations Manager, niet in de workflow en niet in deze repo.
Details en debugroutes: [`zad-deploy.md`](zad-deploy.md).
