---
paths:
  - "src/test/**"
---

# Tests

Beoordeel bij elke codewijziging of er tests bij of om moeten.

## Wat een goede test hier is

- **Een test moet falen als het gedrag verandert.** Vraag bij elke assert: welke wijziging in
  de code laat deze test nog groen? Een assert op alleen non-null, een `any()` in een mock waar
  de waarde ertoe doet, of een mock die het geteste gedrag vervangt, bewijst niets.
- **Happy én unhappy paths**: foutgevallen, edge cases en validatiefouten horen erbij.
- **Kies testdata die het gedrag uitlokt**, niet de makkelijkste die slaagt. Bij collecties
  minstens leeg, één en meerdere elementen; bij grenzen beide kanten (gisteren en vandaag, net
  binnen en net buiten een termijn).
- **Test elke variant van een enum of mapping** die anders loopt, met `@EnumSource` of
  `@ParameterizedTest`, niet alleen de eerste.
- **Zet de klok vast** met een vaste `Clock` in plaats van ruime marges rond `now()`. Een test
  die rond een minuut- of daggrens anders uitvalt, is flaky.
- **Een verwijderde test vraagt een vervanger** voor elk gedrag dat hij dekte.

## Hoe

- **Controllertests** zijn `@QuarkusTest` met REST-assured tegen het echte pad. Mock externe
  diensten op clientniveau met `@InjectMock @RestClient` op de gegenereerde API (`ProfielApi`,
  `SendAMessageApi`) plus Mockito, niet over HTTP.
- **Unittests zonder Quarkus** voor losse logica (`WebhookUrlValidator`, `HashHelper`, adapters
  met gemockte afhankelijkheden). Ze tellen mee voor de coverage en zijn veel sneller.
- **De database**: `EmbeddedPostgresTestResource` start één PostgreSQL 18 (Zonky) per testrun;
  Flyway migreert en `validate` controleert het schema. Elke `@QuarkusTest` deelt die database,
  dus een test ruimt zijn eigen rijen op. Een `@TestProfile` herstart Quarkus en krijgt een eigen
  database. De `V*MigratieTest`-klassen starten hun eigen embedded PostgreSQL.
- **Toestand die de overgangsfunctie niet maakt**, zet je met SQL uit `NotificatieFixtures`
  (die de trigger voor de eigen transactie uitzet met
  `SET LOCAL session_replication_role = replica`) en `TaakFixtures`. Een test die de trigger of
  de SQL zelf toetst, mag eigen SQL bevatten.
- **Een deferred trigger faalt bij de commit**: de databasefout zit als suppressed exception
  onder de `RollbackException`.
- **De webhook** levert in tests aan `WebhookOntvanger`; `LokaleWebhookUrlValidator` (`@Mock`)
  laat de http-URL op localhost toe.
- **Zichtbaarheid hooguit package-private**, met een comment met de reden. Geen `public` voor
  een test en geen reflectie. Moet je meer dan een handvol leden openen, haal er dan een klasse
  uit in plaats van de bestaande verder open te zetten.
- **Testnamen in het Nederlands**: `methode_situatie_verwachting`.

## Fuzzing

Overweeg fuzzing bij input-parsing, validatielogica en security-gevoelige code.
`fuzzing/EndpointFuzzTest` is een `@QuarkusTest` met `@FuzzTest`-methodes; zonder `JAZZER_FUZZ`
draaien ze als regressietest over de opgeslagen corpus. De standalone targets
(`NotificatieVerwerkingFuzzer`, `BerichtTypeFuzzer`, `HashHelperFuzzer`,
`JsonDeserializationFuzzer`) bouwt ClusterFuzzLite via `.clusterfuzzlite/build.sh`. Een fuzzer
met een eigen kopie van productielogica controleert die kopie, niet de productie.
