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
  `methode_situatie_verwachting`: `stuurStatusUpdate_allePogingenMislukt_retourneertFalse`,
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

- `POST /api/nmc/v1/centraal/notificaties`: zoekt via de Profielservice het
  e-mailadres op en verstuurt via NotifyNL.
- `POST /api/nmc/v1/decentraal/notificaties`: de aanroeper levert het e-mailadres
  zelf; geen Profielservice-lookup.
- `POST /api/nmc/v1/notifynl-callback`: ontvangt de afleverstatus van NotifyNL,
  beveiligd met een bearer token (`NotifyNLCallbackAuthFilter`).
- **Statusupdate naar de aanroeper.** Heeft het verzoek een `callbackUrl`, dan
  stuurt `ConsumentCallbackAdapter` bij elke nieuw vastgelegde afleverstatus een CloudEvent naar
  die URL: maximaal drie pogingen met oplopende wachttijd. De `Notificatie` en
  zijn statusgeschiedenis blijven daarna staan.
- **Adresselectie in de Profielservice-respons** (`ProfielServiceAdapter`): eerst
  een e-mailadres met exact de scope Dienstverlener + dienst, dan een met alleen
  de Dienstverlener als scope, dan het default-adres, dan een adres zonder scopes.

Beide create-flows delen `NotificatieService.verstuurNaarEmail(...)` (persist →
NotifyNL → status `SENDING`); de centrale flow doet eerst de Profielservice-lookup.

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
  prioriteit. Het datamodel moet een audittrail van statussen en pogingen per
  notificatie later wel zonder herontwerp toelaten. Het datamodel ondersteunt dat al:
  `Notificatie` houdt de volledige statusgeschiedenis bij (`notificatie_status`, één
  rij per overgang) naast een kopie van status en registratietijd van het laatste
  record op `notificatie` zelf. Die kopie is waar de code op stuurt; de geschiedenis
  is het audittrail.
- **Een tweede schrijver van de statusgeschiedenis moet de `notificatie`-rij locken.**
  Binnen Java schrijft alleen `Notificatie#registreerStatus` zowel `notificatie_status`
  als de kopie (`laatste_status`, `laatste_status_update`), en `@Version` vangt twee
  gelijktijdige schrijvers af. In de database koppelt geen constraint de kopie aan de rij
  met het hoogste `volgnummer`. Wie er een schrijver bij zet (retentiejob, script,
  migratie), leest de rij dus met `SELECT ... FOR UPDATE` — de retentiejob uit
  MinBZK/moza-notificatiemanagementcomponent#46 doet dat met `FOR UPDATE SKIP LOCKED` — of
  laat de kopie en de geschiedenis uit de pas lopen. Een
  ontbrekend `volgnummer` is net zo fataal: `@OrderColumn` laadt daar een null voor in, en
  `Notificatie` gooit dan een `IllegalStateException`.
- **`StatusWaarde`** kent `CREATED`, `SENDING`, `DELIVERED`, `PERMANENT_FAILURE`,
  `TEMPORARY_FAILURE` en `TECHNICAL_FAILURE`, naar de afleverstatussen die GOV.UK
  Notify voor e-mail terugmeldt, plus `ONBEKEND`. Dat laatste is de vangwaarde van
  `NotificatieService#parseStatus` voor een status die de NMC niet kent: die wordt op
  ERROR gelogd en als `ONBEKEND` vastgelegd, zodat hij niet als een bekende uitkomst
  landt. Komt er daarna nog een onbekende waarde binnen, dan is dat voor `volgtOp` een
  herhaling: geen extra record en geen callback, alleen de ERROR-regel met de ruwe waarde.
  Of `onbekend` überhaupt naar de Dienstverlener teruggekoppeld moet worden, staat open in
  MinBZK/MijnOverheidZakelijk#1132. `CREATED` en `SENDING` legt de NMC zelf vast; stuurt NotifyNL ze toch, dan
  weigert `volgtOp` ze. De `CHECK`-constraints in
  `V1__init_notificatie.sql` en `V2__notificatie_statusgeschiedenis.sql` sommen dezelfde waarden
  op; een nieuwe status vraagt dus ook een migratie.
  `moza-portaal/dependencies/omc/swagger.json` bevat een
  `DeliveryReceipt`/`DeliveryStatuses`-schema, maar dat hoort volgens Joeri
  waarschijnlijk bij een ander systeem dan de OMC per Dienstverlener (mogelijk een
  Output Management Systeem of Printstraat). Gebruik die swagger niet als contract
  voordat dat is opgehelderd.
- **Afleverstatussen komen at-least-once en ongeordend binnen.** NotifyNL biedt een
  callback opnieuw aan bij elke niet-2xx, dus de volgorde van binnenkomst zegt niets
  over de volgorde van de gebeurtenissen. `StatusWaarde#volgtOp` bepaalt of een
  binnengekomen status wordt vastgelegd, en onderscheidt alleen de verzendfase
  (`CREATED`, `SENDING`) van wat NotifyNL daarna terugmeldt. Tussen die terugmeldingen
  geldt geen rangorde: NotifyNL kan ná een bezorging alsnog een fout melden, dus elke
  definitieve status volgt op elke andere. Geweigerd worden alleen een herhaling van
  de huidige status en een teruggang naar de verzendfase; een eerdere status die
  terugkomt (A, B, A) wordt wél vastgelegd en doorgegeven.
  Welke uitkomst uiteindelijk telt is nog niet belegd — `isDefinitief` betekent
  "NotifyNL heeft iets teruggemeld", niet "hier komt niets meer overheen".
- **Gebeurtenistijd en registratietijd zijn aparte kolommen.**
  `NotificatieStatus#tijdstip` is wanneer de status ontstond, op de klok van de bron:
  voor een delivery receipt NotifyNL's `completed_at`, met `sent_at` en `created_at`
  als terugval. Geen van die drie is verplicht in hun callbackschema; dan valt de NMC
  terug op de eigen klok. `NotificatieStatus#geregistreerd` is wanneer de NMC de status
  vastlegde, op de eigen klok. Ze lopen uiteen omdat NotifyNL een mislukte callback tot
  5x met 5 minuten ertussen herhaalt. Wat op de eigen klok moet, gebruikt `geregistreerd`
  (kolom `geregistreerd`, gekopieerd naar `laatste_status_update`); `tijdstip` is het tijdstip voor het afleverbewijs en wordt
  nergens op gefilterd of gesorteerd. De volgorde van de geschiedenis komt uit
  `@OrderColumn` op `volgnummer`, niet uit een van beide tijdstippen.
- **De statusupdate naar de Dienstverlener gaat pas ná de commit.**
  `NotificatieService` vuurt een `StatusUpdateOpdracht` af; `StatusUpdateVerzender`
  pakt die op bij `AFTER_SUCCESS` en roept `ConsumentCallbackAdapter` aan. Versturen
  vóór de commit zou de Dienstverlener een status kunnen geven die de NMC daarna
  terugrolt, en zou een DB-connectie bezet houden zolang de HTTP-pogingen duren. De
  observer is bewust een eigen bean: in tests wordt de adapter met `@InjectMock`
  vervangen, en een observer-methode op een mock wordt nooit aangeroepen.

## Technische stack

- **Runtime:** Quarkus 3.39.1, Java 25
- **Build:** één Maven-module, wrapper `./mvnw`
- **API:** contract-first uit `META-INF/openapi.yaml` en
  `META-INF/notifynl-callback-openapi.yaml`, Quarkus REST + Jackson
- **Uitgaande clients:** quarkus-openapi-generator voor Profielservice en
  NotifyNL; een dynamisch gebouwde REST-client voor de callback naar de aanroeper
- **Persistentie:** PostgreSQL 18 + Hibernate ORM Panache + Flyway; in tests een embedded PostgreSQL (Zonky)
- **Test:** JUnit 5, REST-assured, Mockito, Jazzer (fuzzing)
- **Fouten:** RFC 9457 `application/problem+json` via quarkus-http-problem
- **Audit:** LDV-wrapper (`logboekdataverwerking`), standaard uitgeschakeld
- **Image:** jib (geen Dockerfile), `eclipse-temurin:25-jre`, draait als uid 1001
- **Health:** smallrye-health op `/q/health`; readiness bevat een datasource-check

Packages zijn **technisch** ingedeeld: `controller/`, `service/`, `domain/`,
`repository/`, `client/<systeem>/`, `helper/`, `validation/`. De uitzondering is
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
  `x-field-extra-annotation`. `callbackUrl` heeft `format: uri` en wordt daarmee
  een `java.net.URI`, waar Hibernate Validator geen `@Size` voor heeft (HV000030);
  daarom `@ValidCallbackUrl`, die `CallbackUrlValidator` aanroept.
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
(`V2MigratieTest`) starten hun eigen embedded PostgreSQL. Profielservice, NotifyNL en de
callback naar de aanroeper worden gemockt.

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
  meer leest. `V2__notificatie_statusgeschiedenis.sql` doet dat voor `notificatie.status` en
  `notificatie.aangemaakt`.
- **SQL is PostgreSQL 18.** Dev, test en prod draaien hetzelfde dialect, dus
  PostgreSQL-eigen SQL is toegestaan en draait ook in de tests.
- **Houd de migratie en de entity gelijk.** In elk profiel staat
  `schema-management.strategy=validate`: wijkt een entity af van het gemigreerde
  schema, dan start de applicatie niet, en de testsuite dus ook niet.
- **In prod draait Flyway niet bij het starten**
  (`%prod.quarkus.flyway.migrate-at-start=false`). Op ZAD staat
  `QUARKUS_FLYWAY_MIGRATE_AT_START=true` in de deploymentconfig; zonder die
  instelling faalt de container op een lege database.
- Queries gebruiken het JPA-metamodel (`Notificatie_.EXTERNAL_REFERENCE`) in plaats
  van losse strings. De `hibernate-processor` genereert dat; hij staat expliciet in
  `annotationProcessorPaths` omdat classpath-processors sinds JDK 23 niet meer
  vanzelf draaien.
- Schrijf kolomnamen in de migratie met underscores (`external_reference`) en
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
- **Callback-URL's** gaan door `CallbackUrlValidator` (via `@ValidCallbackUrl`) en
  daarna door `CallbackUrlValidator.normaliseer`: de uitgaande REST-client
  vergelijkt het scheme hoofdlettergevoelig. De validator is een denylist op vorm
  en geen volledige SSRF-bescherming; zie de javadoc.

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
  kanttekening bij `StatusWaarde`.
