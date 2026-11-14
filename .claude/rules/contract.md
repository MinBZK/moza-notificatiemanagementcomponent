---
paths:
  - "src/main/resources/META-INF/*.yaml"
  - "src/main/resources/openapi/**"
  - "src/main/java/nl/rijksoverheid/moz/nmc/controller/**"
  - "src/main/java/nl/rijksoverheid/moz/nmc/notifynlcallback/**"
---

# Contracten en controllers

Er zijn twee servercontracten, elk met een eigen execution van de
`openapi-generator-maven-plugin` (`jaxrs-spec`, `interfaceOnly=true`):

| Contract | Interfaces en modellen in | `returnResponse` |
|----------|---------------------------|------------------|
| `src/main/resources/META-INF/openapi.yaml` | `nl.rijksoverheid.moz.nmc.api(.model)` | `true`, zodat de controllers 202 antwoorden |
| `src/main/resources/META-INF/notifynl-callback-openapi.yaml` | `nl.rijksoverheid.moz.nmc.notifynlcallback.api(.model)` | `false` |

- **Het contract is de bron.** Schrijf geen DTO met de hand en bewerk niets onder
  `target/generated-sources`; pas het contract aan en bouw opnieuw. Annotatie-scanning staat uit
  (`mp.openapi.scan.disable=true`); Quarkus publiceert het statische `openapi.yaml` op `/q/openapi`.
- **Controllers implementeren de gegenereerde interface.** De interface draagt pad,
  HTTP-methode, mediatypes en bodyvalidatie; de controller alleen de implementatie. **Geen
  `@POST`, `@Path`, `@Consumes`, `@Produces` of `@Valid` op een controllermethode**: volgens de
  JAX-RS-overervingsregel vervallen dan alle annotaties van de interface voor die methode.
  Geen test bewaakt dat; een hook waarschuwt.
- **Validatie die de generator niet kan uitdrukken** gaat via `x-field-extra-annotation`. Let
  op bij `format: uri`: dat wordt een `java.net.URI`, waar Hibernate Validator geen `@Size` voor
  heeft (HV000030).
- **De webhook staat als callback bij `GET .../wijzigingen`**, met als sleutel `{webhookUrl}`:
  OpenAPI 3.0 kent geen losse webhooks, en de URL komt uit het register.
- **`IdentificatieType` en `NotificatieStatusEvent`** zijn via `importMappings` gekoppeld aan de
  bestaande klassen, zodat er geen tweede klasse ontstaat.
- **Op twee plekken, en gelijk te houden:**
  - de geldige berichttypes: in de `description` van `berichtType` en in de enum `BerichtType`;
  - de contractversie: `info.version` in `openapi.yaml` en `mp.openapi.info.version` in
    `application.properties` (de `API-Version`-header). `ConfiguratiePariteitTest` bewaakt die.
- **Bewust geen `servers:`-blok in `openapi.yaml`**, zodat "Try it out" in Swagger UI op elke
  omgeving naar de eigen host wijst. Het callbackcontract heeft er wel een, met voorbeeldhosts.
- **Een wijziging in het contract raakt de Dienstverlener.** Statuswaarden, velden, statuscodes
  en de vorm van het CloudEvent zijn een belofte aan afnemers. Een breuk vraagt een
  versieverhoging en afstemming; noem hem expliciet in de PR. `GepubliceerdeSpecTest` toetst de
  statuswaarden en redenen in het gepubliceerde document.
- **Teksten in het contract zijn voor de Dienstverlener**: beschrijf wat een endpoint doet en
  oplevert, niet het interne mechanisme van de NMC.
- **Clientcontracten** staan in `src/main/resources/openapi/` (`profielservice_api.json`,
  `notifynl_api.yaml`); quarkus-openapi-generator bouwt de clients in
  `client/<systeem>/generated`. Het Profielservice-contract is een kopie en wordt niet
  automatisch gesynchroniseerd.
