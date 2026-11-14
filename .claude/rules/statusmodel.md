---
paths:
  - "src/main/java/nl/rijksoverheid/moz/nmc/domain/**"
  - "src/main/java/nl/rijksoverheid/moz/nmc/service/**"
  - "src/main/java/nl/rijksoverheid/moz/nmc/repository/**"
  - "src/main/java/nl/rijksoverheid/moz/nmc/job/**"
---

# Statusmodel, taken en eventlog

Hoe de mechanismen werken staat in `docs/architectuur.md`. Dit zijn de invarianten die je bij
een wijziging niet mag breken.

## Status en versie

- **`Overgangsfunctie` is de enige schrijver van de status.** Hij vergrendelt de rij
  (`PESSIMISTIC_WRITE`), toetst aan `Overgangsregels`, verhoogt de versie met één en schrijft
  het event, in de transactie van de aanroeper.
- **Elke Hibernate-update van `notificatie` verhoogt `@Version` en vraagt een event**; de
  deferred trigger `notificatie_overgang` weigert anders de commit. Een schrijver zonder
  overgang gaat met native SQL in `NotificatieRepository`, zonder versie en event.
- **Lees een entity die je gaat wijzigen pas ná het vergrendelen.** Beslis niet op toestand
  die je vóór de lock las: een andere transactie kan hem intussen veranderd hebben.
- **De versleutelde kolommen en `kek_versie` staan op `updatable = false`.** Een overgang op een
  eerder geladen entity schrijft anders een gewiste of geherwrapte sleutel terug.
- **Een eindstatus plant een `WISSEN`-taak** waarna de sleutel weg is. Een overgang die een
  eindstatus verlaat, moet ermee rekenen dat de sleutel al gewist kan zijn.

## Taken

- **Elke bijwerking is een taak** in `taak`, geclaimd met `FOR NO KEY UPDATE SKIP LOCKED`. Er
  draaien in productie meerdere pods: elke handler moet een tweede worker op dezelfde taak
  verdragen. Controleer na een externe aanroep of de claim nog van jou is.
- **Een externe aanroep gaat buiten de transactie**, met een transactie ervoor en erna. Houd
  geen databaseverbinding of lock vast tijdens HTTP.
- **Een lease moet langer zijn dan de langste stap**, of de handler verlengt hem vóór die stap.
  Anders claimt een tweede pod dezelfde taak.
- **Onderscheid een externe fout van een eigen fout.** Een externe storing (5xx,
  verbindingsfout, 429) stelt uit zonder poging te verbruiken; alleen een fout in het verzoek
  van de NMC kost een poging. Een onverwachte exception telt als poging.
- **Een query op `taak` noemt de `soort`**: de tabel is per soort gepartitioneerd.

## Ontvangen en terugmelden

- **Receipts komen at-least-once en ongeordend.** Een herhaalde of oudere receipt verandert
  niets; ordenen gaat op het tijdstip in de receipt, niet op volgorde van binnenkomst.
- **Een tijdstip van buiten** (NotifyNL, Dienstverlener) kan in de toekomst of ver in het
  verleden liggen. Begrens het voordat je er termijnen mee berekent.
- **Webhook en feed lezen het eventlog**; er is geen push per overgang. `id` van het
  CloudEvent is `event.uuid`, niet de identity-id.
- **`transactieId` is een `xid8`.** Bind hem via `TransactieIdType`; in een rijvergelijking of
  insert geef je het type mee met `setParameter(naam, waarde, Event_.transactieId.getType())`.

## Externe input

- **Begrens alles wat van buiten komt**: aantallen, lengtes van sleutels én waarden, de omvang
  van een response die je inleest, en de frequentie van aanroepen. Een limiet die per pod telt,
  is met N pods N keer zo hoog; zeg dat erbij.
- **Een aanroep die opnieuw kan komen, moet idempotent zijn** of het expliciet niet zijn, met de
  reden.
- **Gebruik de geïnjecteerde `Clock`**, nooit `now()` zonder klok.
