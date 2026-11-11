# Testverbeterplan NotificatieManagementComponent

**Peildatum: 11 november 2026.** Vorige peildatums: 7 oktober, 30, 23 en 7 september, 12 augustus 2026.

Dit document beschrijft de afstand tussen het **testplan** en de huidige werkelijkheid in
**moza-notificatiemanagementcomponent (NMC)**. Het bevat eenmalige acties. Zodra een item is afgerond
dient het verwijderd te worden.

Deze peildatum beschrijft de stand van branch `1150-event-driven-core` (PR #66), de event-driven core
die `main` vervangt. Wat hieronder over de code staat, geldt voor die branch.

De teststrategie en het testplan staan in
[hoofdstuk 12 *Testen*](https://docs.mijnoverheidzakelijk.nl/workspace/documentation#testen) van de
MOZA-architectuurdocumentatie. Dat hoofdstuk is leidend. Verwijzingen hieronder gaan naar sectienamen
daarin, niet naar paragraafnummers.

De Profielservice heeft haar [eigen plan](https://docs.rijksapp.nl/docs/23220aa3-e724-4356-986a-7ec5801f8dcf/).
Een aantal items geldt voor beide services. Deze staan in beide documenten, toegespitst op de eigen
situatie. Wie zo'n item oppakt, werkt beide documenten bij.

De gapnummers zijn stabiel: een afgerond gap blijft als *vervallen* staan, zodat verwijzingen vanuit
stories en het Profielservice-plan blijven kloppen. Elk open gap verwijst naar de story waarin het
werk staat.

Door de werkzaamheden aan de services veroudert dit document snel. Het is daarom verstandig het goed
bij te houden.

## Stand van zaken

|                            | NMC                                                                                       |
| -------------------------- | ----------------------------------------------------------------------------------------- |
| Rol in het contract        | consumer (Profielservice, NotifyNL); provider (eventfeed en webhook naar Dienstverleners) |
| Java                       | 25                                                                                        |
| Coverage-gate              | 90/75 (INSTRUCTION/BRANCH), draait in CI                                                  |
| Testdatabase               | embedded PostgreSQL (zonky, zonder Docker)                                                |
| Flyway draait in tests     | ja, de volledige set op PostgreSQL; eigen migratietests voor V2, V5, V8, V14 en V16       |
| Unitlaag (containervrij)   | 29 van 67 klassen (43%), zonder Quarkus en zonder database                                |
| Externe services in tests  | mocks op de client-interface; webhook en NotifyNL-foutmelding wél over echt HTTP          |
| OpenAPI-validatie in tests | nee (wel een eigen spec-controle op enums en het CloudEvent-schema)                       |
| Fuzzing                    | ja (Jazzer + ClusterFuzzLite)                                                             |
| Deployomgeving per PR      | ja (ZAD `pr-<nummer>`)                                                                    |

De NMC loopt voor op testbaar ontwerp. Met de event-driven core zijn twee structurele gaps gedicht:
de tests draaien op het productiedialect, en reflectie is uit de testopzet verdwenen. Zij mockt haar
uitgaande calls naar de Profielservice en NotifyNL echter nog altijd op de Java-interface, en die
mocks zitten nu in negen testklassen in plaats van twee.

De teststrategie kent een doorlopend afrondingscriterium: elk risico uit het *Risicobeeld* moet
aanwijsbaar zijn met minstens één test.

Voor **R8** (onjuiste terugmelding over aflevering) is de componentlaag ruim gedekt. `OvergangsregelsTest`
en `OvergangsfunctieTest` dekken de toegestane statusovergangen, `ReceiptVerwerkerTest` en
`NotifyNLCallbackBotsingTest` de verwerking van at-least-once en ongeordend binnenkomende receipts,
`NotifyNLNavraagTaakHandlerTest` en `BezorgingVaststellenTest` het vaststellen van een uitkomst
zonder receipt. De terugmelding zelf loopt via de eventfeed (`EventfeedTest`) en de webhook
(`TerugkoppelTaakHandlerTest`, `WebhookClientIntegratieTest`). `CloudEventContractTest` toetst het
CloudEvent per status aan het schema in het gepubliceerde `/q/openapi`. Wat voor R8 nog ontbreekt is
de contractlaag met een echte tegenpartij (gap 5) en de e2e-laag (gap 15).

Voor **R9** (onterecht uitgebleven uitkomst) zijn de uitsluitingsregels veranderd. Een notificatie
verdwijnt niet meer zodra de terugmelding slaagt. De wistaak wist de sleutel zeven dagen na de
eindstatus (`nmc.wissen.termijn`), waarna de rij pseudoniem is; het onderhoud verwijdert de rij na de
bewaartermijn van het afleverbewijs, 365 dagen (`nmc.afleverbewijs.bewaartermijn`). Beide regels zijn
getest, ook op het geval dat er nog niet onder valt (`wistaak_voorDeWistermijn_…`). Maar
`WisTaakHandler` en `OnderhoudTaakHandler` vergelijken met een bare `now()`, dus die grensgevallen
liggen niet vast op het moment zelf; zie gap 7.

**De vorm van de piramide is stabiel, niet beter.** Het aantal testklassen ging van 30 naar 67, en de
containervrije laag groeide mee van 13 naar 29. Het aandeel bleef 43%. De drie migratietests die zelf
een embedded PostgreSQL starten (V5, V14, V16) tellen hier niet als containervrij. De doorlooptijd van
de PR-checks blijft een punt om op te volgen: de teststrategie noemt 15 minuten als streven.

### Pipeline

| Check                         | NMC                                                    |
| ----------------------------- | ------------------------------------------------------ |
| `verify` in CI                | ja, maar met de systeem-`mvn` in plaats van de wrapper |
| Coverage-gate draait          | ja (gebonden aan `verify`)                             |
| Testrapporten als CI-artifact | nee                                                    |
| Diff-coverage per PR          | nee                                                    |
| Echte PostgreSQL in tests     | ja (embedded)                                          |
| Pact                          | geen                                                   |
| Spectral / oasdiff            | nee                                                    |
| Mutatietesten                 | nee                                                    |
| ClusterFuzzLite               | ja (PR + wekelijks)                                    |
| Scorecard                     | ja (wekelijks)                                         |
| CodeQL                        | ja                                                     |
| DAST                          | nee                                                    |
| Deployomgeving per PR         | ja (ZAD `pr-<nummer>`)                                 |

## Gewijzigd sinds 7 oktober 2026: de event-driven core

De event-driven core verandert het testbeeld op vijf punten. De gaps hieronder zijn daarop bijgewerkt.

- **Versturen loopt via een takentabel.** De aanname antwoordt 202 na één insert plus een verzendtaak;
  een `TaakWorker` claimt taken met `FOR UPDATE SKIP LOCKED` en roept de Profielservice en NotifyNL
  aan buiten elke transactie. De transactie- en connectiepoolkwestie uit de vorige versie van gap 16
  bestaat daardoor niet meer.
- **De terugmelding aan de Dienstverlener loopt via een eventfeed met cursor en een webhook**, niet
  meer via een `callbackUrl` op het verzoek. De webhook-URL komt uit het dienstverlenerregister en
  gaat door `WebhookUrlValidator`; de levering is ondertekend met een JWT, met de sleutels op
  `/.well-known/jwks.json`.
- **De tests draaien op embedded PostgreSQL** in plaats van H2 (#1151).
- **Ontvanger en personalisatie staan versleuteld op de notificatierij**, met een sleutel per
  notificatie onder een KEK met versienummer. `SleutelbeheerTest`, `ConfigKekProviderTest` en
  `VersleuteldeOpslagIntegratieTest` dekken dat.
- **De retentiejob is vervangen** door een wistaak en een onderhoudstaak (sleutels wissen,
  notificaties en eventpartities opruimen, sleutels herwrappen).

## Gaps en acties

Genummerd op prioriteit. De pijl geeft de actie.

### Afhankelijkheden tussen de acties

- gap 2 (WireMock voor de uitgaande clients) → gap 5 (echte consumertests) → Pact Broker
- gap 9 (containerscheiding afdwingen) → gap 12 (mutatietesten)
- gap 7 (`Clock`) → vaste grensgevallen voor de uitsluitingsregels van R9

### Hoog

#### 1. De CI-build is nog niet af

De workflow draait `verify`, dus de coveragegate gaat af. Drie dingen resteren. De workflow roept de
systeem-`mvn` aan in plaats van `./mvnw`, waarmee de buildversie van de runner afhangt in plaats van
van de repo. Er worden geen Surefire- of JaCoCo-rapporten als artifact gepubliceerd, wat het testplan
onder *Pipeline-integratie* wel vraagt. En er is geen diff-coverage per pull request.

→ `./mvnw` gebruiken, rapporten uploaden, diff-coverage toevoegen. Story: nog niet aangemaakt.

#### 2. Componenttests mocken de gegenereerde REST-clients

`@InjectMock @RestClient` op `ProfielApi`, `SendAMessageApi` en `GetMessageDataApi`, in negen
testklassen: de twee intakecontrollertests, `NotifyNLCallbackControllerTest`, `AannameServiceTest`,
`VerzendTaakHandlerTest`, `HerverzendingTest`, `NotifyNLNavraagTaakHandlerTest`,
`VersleuteldeOpslagIntegratieTest` en `EndpointFuzzTest`. Geen HTTP, geen serialisatie, geen
foutafhandeling, geen contract. WireMock komt in de pom niet voor. Dit is een directe overtreding van
de grondregel *"Stub op de transportgrens. Mock nooit een gegenereerde REST-client-interface in een
componenttest."*

De webhook (`WebhookClientIntegratieTest`) en het uitlezen van een NotifyNL-foutmelding
(`NotifyNLFoutmeldingIntegratieTest`) lopen wél over echt HTTP, naar een testendpoint in de applicatie
zelf. Dat is de transportgrens, maar geen nagebootste tegenpartij.

→ Omzetten naar WireMock-stubs op HTTP-niveau, inclusief de unhappy paden (timeout, 5xx, misvormde
body). Story: nog niet aangemaakt.

#### 3. ~~Tests draaien op H2~~ — *vervallen*

Sinds #1151 draaien de tests op embedded PostgreSQL, en elke `@QuarkusTest` brengt de volledige
migratieset aan op het productiedialect. De migraties die bestaande gegevens omzetten hebben een eigen
test (V2, V5, V8, V14, V16). De keuze voor de embedded variant boven Testcontainers is gemaakt in
#1151: geen Docker nodig, lokaal en in CI.

Wat resteert hoort niet hier maar bij migratieveiligheid op releaseniveau (#1246): expand/contract
over releases heen en een geteste rollback. Een migratie met `CREATE INDEX CONCURRENTLY` draait buiten
een transactie en is er nu niet; komt die er, dan heeft zij een eigen test nodig.

#### 4. Geen OpenAPI-conformiteitsvalidatie

De spec wordt hier handmatig onderhouden (`mp.openapi.scan.disable=true`) en kan dus echt afdrijven
van de implementatie, terwijl consumers er hun client uit genereren. Dat is precies het geval waarin
het testplan deze validatie de enige bewaker van het verschil tussen spec en implementatie noemt. Met
de eventfeed, de bevestiging en de JWKS-route is het oppervlak gegroeid. `GepubliceerdeSpecTest`
controleert dat statussen en redenen als enum in het gepubliceerde document staan, en
`CloudEventContractTest` toetst het CloudEvent aan het schema. Dat zijn twee schema's, geen
conformiteitsvalidatie van alle requests en responses.

→ `swagger-request-validator` plus de `OpenApiValidationTest`-basisklasse overnemen uit de
Profielservice. Story: nog niet aangemaakt.

#### 5. Het "contract" is een gesloten lus — *(ook in het Profielservice-plan)*

Het pactbestand van de Profielservice is door de provider zelf geschreven en wordt tegen zichzelf
geverifieerd. De echte consumer, deze service, heeft geen pact-dependency. Dit is het enige gat dat
risico R4 uit het *Risicobeeld* raakt.

Aan de R8-kant is de NMC zelf provider: Dienstverleners lezen de eventfeed en ontvangen de webhook.
`CloudEventContractTest` legt de vorm van het CloudEvent vast tegen het gepubliceerde schema. Dat is
een eigen assertie, geen afspraak met een tegenpartij; een Dienstverlener die de feed of de webhook
consumeert heeft nog geen contract.

→ Na gap 2: echte consumertests hier; daarna pas de broker. Story: #748 bestaat, maar is een
verkenning; een uitvoerbare story ontbreekt nog.

#### 6. Callback-auth is niet volledig negatief getest

`NotifyNLCallbackAuthFilter` vergelijkt constant-time (`MessageDigest.isEqual`), maar heeft geen eigen
testklasse. In `NotifyNLCallbackControllerTest` staan alleen een ontbrekend en een ongeldig token. Het
testplan vraagt onder *Security- en robuustheidstesten* expliciet vier gevallen: ontbrekend, leeg,
verkeerd en bijna-goed. Los daarvan ontbreekt de `WWW-Authenticate`-header op de 401 (#1543).

→ De twee ontbrekende gevallen toevoegen, en de misbruikcases bij het filter zelf plaatsen in plaats
van bij de controller. Story: nog niet aangemaakt.

#### 7. `Clock` wordt maar deels gebruikt — *(ook in het Profielservice-plan)*

Er is een `ClockProducer`, en `TaakWorker`, `TaakClaimer`, `Verzendbudget`, `Eventfeed` en
`Aanroeplimiet` krijgen de `Clock` geïnjecteerd. Ruim twintig plekken roepen nog direct `now()` aan,
waaronder de twee uitsluitingsregels van R9: `WisTaakHandler` (sleutel wissen na de wistermijn) en
`OnderhoudTaakHandler` (rijen verwijderen na de bewaartermijn). Ook `NotifyNLNavraagTaakHandler`,
`ControleTaakHandler`, `VerzendTaakHandler`, `ReceiptVerwerker`, `AannameService` (dagquotum) en de
constructors van `Notificatie` en `Event` gebruiken de wandklok. Zolang dat zo is, ligt het geval dat
er nét niet onder valt niet vast op het moment zelf, en is R9 formeel gedekt en feitelijk niet.

→ De resterende `now()`-aanroepen vervangen door de geïnjecteerde `Clock`, te beginnen bij de wis- en
onderhoudstaak. Voeg daarna per uitsluitingsregel een geval toe op de grens, dat er níét onder valt en
dus moet blijven staan. Story: nog niet aangemaakt.

### Middel

#### 8. ~~Reflectie in de testopzet~~ — *vervallen*

Er staat geen `java.lang.reflect` meer in de testmap. `NotificatieServiceTest` is met de event-driven
core verdwenen; de vervangende tests bouwen hun toestand via `NotificatieFixtures` en `TaakFixtures`.

#### 9. De containervrije laag is niet apart te draaien — *(ook in het Profielservice-plan)*

Het testplan schrijft onder *Grondregels voor testautomatisering* voor dat de scheiding tussen
container en geen container wordt afgedwongen, met een eigen ingang voor de snelle laag. Die is er
niet: nul `@Tag` in de repo, en 35 van de 67 testklassen starten Quarkus in dezelfde surefire-run als
de klassen die dat niet doen. Drie migratietests zonder Quarkus starten zelf een embedded PostgreSQL;
ook die horen niet in de snelle laag.

→ Drie stappen: `@Tag("unit")` op de 29 klassen zonder Quarkus en zonder database; surefire een tweede
ingang geven met `<groups>${test.groups}</groups>` en een lege default; en een ArchUnit-test die de
scheiding afdwingt in plaats van haar te benoemen, inclusief de omgekeerde regel dat een klasse met
"Unit" in de naam geen container of database mag starten. Deze repo heeft nog geen ArchUnit, dus die
dependency komt erbij. Story: nog niet aangemaakt.

#### 10. Bevroren spec-kopie als bron voor de gegenereerde client

`src/main/resources/openapi/profielservice_api.json` is sinds 2 juli 2026 niet ververst, terwijl de
Profielservice haar contract sindsdien meermaals heeft gewijzigd, waaronder een **brekende** wijziging
(een onbekende dienstverlener in het pad geeft nu 404). De kopie wijkt dus aantoonbaar af. Er is nog
geen drift-check.

→ Korte termijn: verversen plus een CI-check die drift signaleert. Structureel: vervangen door de
broker-flow uit gap 5. Story: nog niet aangemaakt.

#### 11. De coverage-afspraak is niet één

De Profielservice staat op LINE/BRANCH 85/80. De NMC meet op INSTRUCTION (90) plus BRANCH (75), en het
testplan raadt INSTRUCTION af omdat het gevoelig is voor bytecode-details en niet vergelijkbaar is
tussen services.

→ De `check`-regel omzetten naar LINE + BRANCH en het huidige niveau vasthouden. Story: nog niet
aangemaakt.

#### 12. Geen mutatietesten — *(ook in het Profielservice-plan)*

Afhankelijk van gap 9: PIT draait de suite opnieuw per mutant en heeft een selectie nodig om op te
mikken, namelijk de tests zonder Quarkus en zonder database. PIT is de enige bytecode-manipulerende
tool die op JDK 25 nog onbewezen is.

→ Na gap 9, en na verificatie dat PIT op JDK 25 draait. Story: #1460.

#### 13. Geen ADR-/Spectral-lint of OpenAPI-breaking-change-check in CI — *(ook in het Profielservice-plan)*

Afwezig in deze repo, en hier weegt het zwaarder dan bij de Profielservice: de spec wordt handmatig
onderhouden en er is geen tegenhanger zoals `OpenApiContractDriftTest`. Met de eventfeed en de webhook
is de NMC bovendien zelf provider geworden; een brekende wijziging raakt elke Dienstverlener die de
feed leest.

→ Spectral- en oasdiff-jobs toevoegen aan de PR-pipeline. Story: nog niet aangemaakt.

#### 14. Geen dekking op de LDV-verwerkingslogging — *(ook in het Profielservice-plan)*

De aanname-endpoints en de eventfeed dragen `@Logboek`, maar geen enkele test controleert dat de
registratie plaatsvindt, met welke betrokkene en met welke uitkomst. Daarnaast ontbreekt de registratie
van de verzending zelf: die gebeurt in een worker, en de `@Logboek`-interceptor werkt alleen op een
REST-aanroep. Het `processingActivityId` is nog een placeholder (#754).

→ Vastleggen wat gelogd moet worden en dekking opbouwen. R3 (ontbrekende of onjuiste
verwerkingslogging) is voor deze service nu niet aanwijsbaar gedekt. Story: #1359 (toetsen in de
testsuite), onder #1526.

#### 15. Geen e2e-suite in de pipeline — *(ook in het Profielservice-plan)*

ADR 0023 kiest Bruno voor de nachtelijke run, met Playwright voor journeys met polling of tijdgebonden
asserties en REST Assured als een journey liever in Java blijft. De NotifyNL-mock in `moza-mock` maakt
het mogelijk de notificatiestappen mee te nemen zonder dat er echte mail uitgaat, en #1141 heeft de
nachtelijke run tegen ZAD `stable` ingericht.

Voor deze service is de asynchrone bezorgstatus de eerste echte kandidaat: die loopt van de NMC via
NotifyNL terug naar de NMC en vandaar via de eventfeed of de webhook naar de Dienstverlener, dus geen
enkele test van één losse service kan hem afdekken. Dat is precies wat het toelatingscriterium vraagt.
Met de event-driven core is de journey veranderd: de aanname antwoordt 202, en de uitkomst moet
gepolld worden op de feed of ontvangen op een webhook. De FSC-route naar de NMC is de tweede
kandidaat; die is in september met Bruno handmatig doorlopen tegen ZAD.

→ De journey "notificatie aannemen tot bezorgstatus in de feed" toevoegen aan de nachtelijke run.
Story: nog niet aangemaakt.

#### 16. Geen performancetests — *(ook in het Profielservice-plan)*

Sinds de event-driven core verschuift het risico onder load van de connectiepool naar de
taakachterstand en het verzendbudget. Dat is het soort gedrag dat geen functionele test laat zien en
een load- of soak-run wel.

→ Leg eerst het workloadmodel en een budget per endpoint vast. Daarna een lichte, regelmatige run en
een soak-scenario met een trage of niet-antwoordende Profielservice of NotifyNL. Story: #1248.

### Laag

#### 17. Geen DAST — *(ook in het Profielservice-plan)*

→ ZAP API-scan toevoegen aan de nachtelijke build tegen de ZAD-omgeving. Story: #1171.

#### 18. De buildjob draagt `contents: write`

De hele `build`-job heeft schrijfrechten omdat de dependency-submission-stap die nodig heeft, ook op
pull requests. De Profielservice heeft dit in een aparte job gezet die alleen op `main` draait.

→ Dezelfde splitsing overnemen. Story: nog niet aangemaakt.

### Nieuw

#### 19. Taakverdeling over meerdere pods is niet waargenomen

In productie draaien minimaal drie pods tegen één database, en al het achtergrondwerk loopt via de
takentabel. `TaakClaimerTest` toetst met twee threads in één JVM dat geen taak twee keer wordt
uitgegeven; dat dekt de locksemantiek, niet losse pods met eigen connectiepools en schedulerticks. Gaat
de verdeling mis, dan faalt er niets zichtbaar.

→ Story: #1110.

#### 20. De cursor van de eventfeed wordt niet gefuzzed

De cursor komt van de Dienstverlener binnen en wordt door de NMC ontleed. Dat is input-parsing op een
extern koppelvlak, het geval waarvoor de teststrategie fuzzing vraagt. `CursorTest` dekt de bekende
gevallen; de bestaande fuzztargets raken de cursor niet.

→ Een fuzztarget voor het ontleden van de cursor toevoegen. Story: nog niet aangemaakt.
