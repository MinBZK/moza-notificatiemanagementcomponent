---
paths:
  - "src/main/resources/db/migration/**"
  - "src/main/java/nl/rijksoverheid/moz/nmc/domain/**"
---

# Migraties en schema

- **Migraties zijn onveranderlijk na toepassing.** Wijzig een bestaande `V*.sql` nooit; voeg
  een nieuwe toe in `src/main/resources/db/migration/`. Flyway bewaart een checksum per script:
  een gewijzigd script laat de migratie falen op elke database waar het al gedraaid heeft, ook
  op een previewcluster. De volgende is **V17**; V11 ontbreekt en blijft leeg.
- **Houd de migratie en de entity gelijk.** In elk profiel staat
  `schema-management.strategy=validate`: wijkt een entity af van het schema, dan start de
  applicatie niet, en de testsuite dus ook niet.
- **Kolomnamen met underscores** (`eindstatus_op`), en met `@Column(name = ...)` expliciet op
  de entity.
- **SQL is PostgreSQL 18**, in dev, test en prod. PostgreSQL-eigen SQL mag en draait ook in de
  tests. SQL-keywords in hoofdletters.
- **Expand/contract bij een kolomwijziging.** Voeg eerst de nieuwe kolom toe, vul hem in
  dezelfde migratie uit de oude, en laat de `DROP COLUMN` pas volgen als niets hem meer leest.
  De contract-migratie gaat in een latere uitrol dan de expand, als geen pod met de oude code
  meer draait. V4 (expand), V5 (overstap en backfill) en V6 (contract) zijn het voorbeeld.
- **Gepartitioneerde tabellen (`event`, `taak`) hebben de partitiesleutel in de primary key.**
  Een query op `taak` noemt daarom de `soort`, anders zoekt PostgreSQL in elke partitie. Een
  nieuwe `TaakSoort` vraagt een migratie met een nieuwe partitie.
- **Een nieuwe waarde in `NotificatieStatus` of `PogingStatus`** vraagt een migratie voor de
  `CHECK`-constraint (V4), en voor `NotificatieStatus` ook een regel in `Overgangsregels`.
- **Een index op een gevulde tabel gaat met `CREATE INDEX CONCURRENTLY`.** Een gewone
  `CREATE INDEX` laat elke `INSERT`, `UPDATE` en `DELETE` wachten tot de index klaar is.
  `CONCURRENTLY` kan niet in een transactie, dus zo'n statement krijgt een eigen migratie met
  `-- flyway:executeInTransaction=false`. Faalt de bouw halverwege, dan blijft er een `INVALID`
  index achter die handmatig gedropt moet worden. V3 en V9 doen het bewust zonder, omdat die
  indexen er vóór de eerste productiedata waren; V16 omdat de retentiejob het register tot dan
  op zeven dagen hield.
- **De databaserol heeft timeouts** (V16): `statement_timeout` 30s, `transaction_timeout` 1min,
  `idle_in_transaction_session_timeout` 30s. Ze gelden ook voor Flyway. Een migratie die langer
  kan duren zet ze uit met `SET LOCAL statement_timeout = 0` en `SET LOCAL transaction_timeout = 0`.
  Zonder transactie (`executeInTransaction=false`) doet `SET LOCAL` niets; gebruik daar
  `SET statement_timeout = 0`, anders breekt het statement na 30 seconden af.
- **Een `UPDATE` op `notificatie` in een migratie zet deferred triggercontroles klaar**, en
  zolang die openstaan weigert PostgreSQL DDL op die tabel ("pending trigger events"). Zet na
  zo'n update `SET CONSTRAINTS notificatie_overgang IMMEDIATE`, zoals V16 doet.
- **Een tweede schrijver van `notificatie` buiten Hibernate** (script, migratie, backfill) moet
  de rij vergrendelen en per nieuwe versie een event schrijven. De trigger controleert de versie
  en het bestaan van het event, maar niet of de overgang is toegestaan of past bij het event.
- **In prod draait Flyway niet bij het starten** (`%prod.quarkus.flyway.migrate-at-start=false`).
  Op ZAD staat `QUARKUS_FLYWAY_MIGRATE_AT_START=true` in de deploymentconfig.
- **Queries gebruiken het JPA-metamodel** (`Poging_.NOTIFY_ID`) in plaats van losse strings.
  De `hibernate-processor` staat expliciet in `annotationProcessorPaths`, omdat
  classpath-processors sinds JDK 23 niet meer vanzelf draaien.
- **Controleer een nieuwe migratie** met `./mvnw verify` (Flyway en `validate` draaien in de
  tests) en tegen een echte database met `podman compose up -d` en `./mvnw quarkus:dev`.
  Een migratie met `CONCURRENTLY` draait in de tests mee, maar een lege testdatabase zegt niets
  over de duur op productiedata.
