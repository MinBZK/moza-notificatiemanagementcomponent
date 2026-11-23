# Plan: FSC tussen NMC en moza-mock op de lokale Fundament-sandbox (fase 2)

**Status:** Uitgevoerd

Issue: MinBZK/MijnOverheidZakelijk#1637. Vervolg op `2026-11-19-nmc-op-fundament.md` (fase 1).

## Context

Op het fieldlab (23-25 november 2026) zijn NMC en de Profielservice elk een eigen FSC-peer in een
groep met een Directory van een ander (`fsc-fieldlab`, met certportal en certmanager van het
OpenFSC-team). Hoe die certificaten en de Directory er precies uitzien, weten we nog niet. Lokaal
bootsen we dat na: de sample-installatie `fsc-demo` (Self mode) speelt Directory en certportal, en
`nmc` en `moza-mock` doen mee in External mode. Upstream is External mode alleen met validatie- en
values-tests gedekt (`openfsc-operator/README.md` in de Fundament-repo).

```text
fsc-demo   (Self)      Directory + groeps-CA (cert-manager-Issuer fsc-group)   peer 12345678901234567899
nmc        (External)  inway + outway default                                  peer 11111111111111111111
moza-mock  (External)  inway default → moza-mock:8080                          peer 22222222222222222222
```

NotifyNL blijft direct: of de echte NotifyNL achter FSC komt, is een vraag voor het NotifyNL-team.

## Keuzes

- **Eén `FSCInstallation` per namespace**, met de peer-ID als placeholder: er is nog geen eigen OIN.
- **Groepscertificaten per component**, net als in Self mode: een voor de Manager
  (`spec.certificate`) en een voor de gateway (`inways[].certificate` / `outways[].certificate`). De
  SAN's moeten op de servicenamen passen, anders start de inway niet.
- **Certificaten uit de Issuer `fsc-group` van `fsc-demo`**, daarna gekopieerd naar de peer-namespace.
  Op het fieldlab komen ze uit de certportal; de vorm (`kubernetes.io/tls`, met de peer-ID als
  `serialNumber`) is dezelfde.
- **Grant link** op de outway, zodat de NMC geen `Fsc-Grant-Hash` hoeft mee te sturen: geen codewijziging.
- **`values-lokaal-fsc.yaml`** als extra laag op `values-lokaal.yaml`, zodat de directe variant blijft
  bestaan.

## Stappen

1. Vier `Certificate`s in `fsc-demo` (Manager en gateway per peer), en de Secrets plus een trust anchor
   (alleen het CA-certificaat) gekopieerd naar `nmc` en `moza-mock`.
2. Twee `FSCInstallation`s in External mode, met `fsc-demo` als Directory.
3. In de FSC Controller van moza-mock de service en de publicatie; in die van de NMC de verbinding via
   de Directory ("Toegang aanvragen"), getekend door moza-mock; daarna de grant link.
4. De NMC naar zijn eigen outway (`values-lokaal-fsc.yaml`).

De commando's staan in `docs/fundament-deploy.md`, onder "FSC".

## Verificatie

1. Beide `FSCInstallation`s `Active`; de Directory kent beide peers.
2. Een centrale notificatie eindigt op `BEZORGD`; de logs van de outway (nmc) en de inway (moza-mock)
   tonen de aanroep, met de grant van de verbinding.
3. Non-happy: de vaste BSN's van de Profielservice-mock geven dezelfde statussen als in fase 1.

## Uitkomst

Op 20 november 2026 lokaal uitgevoerd.

- Beide peers waren binnen anderhalve minuut `Active`. De inway van moza-mock herstartte één keer,
  omdat hij er eerder was dan zijn Controller; dat lost zichzelf op.
- De Directory in `fsc-demo` kent beide peers. External mode werkt dus, in elk geval binnen één cluster.
- De publicatie van moza-mock werd door de Directory automatisch getekend (`autoSignGrants`); de
  verbinding moest moza-mock zelf tekenen.
- De NMC belt `http://fsc-outway-default.nmc.svc/profielservice/…`. De outway logt de route
  `11111111111111111111` → `22222222222222222222` met de grant van de verbinding (`$1$3$…`), en de
  inway van moza-mock stuurt de aanroep door naar de mock.
- Statussen, gelijk aan fase 1:

  | Identificatie | Mock | Uitkomst in de NMC |
  |---|---|---|
  | KVK 68750110 | 200 | `BEZORGD` |
  | BSN 999996915 | 404 | `NIET_BEZORGBAAR`, reden `GEEN_CONTACTGEGEVENS` |
  | BSN 999991401 | 500 | `IN_VERZENDING`, verzendtaak uitgesteld; na vijf minuten ten onrechte `VERZONDEN` (zie hieronder) |
  | BSN 111222333 | 200 zonder voorkeur | `NIET_BEZORGBAAR`, reden `GEEN_CONTACTGEGEVENS` |

- Daarna kreeg de NMC ook een inway, want dienstverleners horen de NMC via FSC te bellen. De outway
  van `fsc-demo` speelde dienstverlener RVO: `POST /nmc/api/nmc/v1/centraal/notificaties` via die
  outway gaf 202. De inway van de NMC logde de aanroep, en de notificatie ging via de outway van de NMC
  naar de Profielservice en eindigde op `BEZORGD`. Zo loopt FSC beide kanten op.

### Valkuilen

- In "Contracten → nieuw" staat een peer pas in de keuzelijst als je Manager hem kent. Een verbinding
  naar een nieuwe peer vraag je daarom aan via **Directories → service → Toegang aanvragen**; dan
  staan peer en outway-thumbprint al ingevuld.
- De grant link en een `Fsc-Grant-Hash` horen bij de **verbinding** (`$1$3$…`), niet bij de
  publicatie (`$1$2$…`). Met de publicatie geeft de outway `UNKNOWN_GRANT_HASH_IN_HEADER`.

- Een contract is in de Controller standaard twee dagen geldig. Op 23 november waren publicatie en
  verbinding voor de service `nmc` verlopen: de outway van `fsc-demo` gaf 400
  `UNKNOWN_GRANT_HASH_IN_HEADER`, met een lege lijst `grant_links`, terwijl de grant link nog in de
  database stond. Een herstart van de outway hielp niet. Opgelost met nieuwe contracten (een jaar
  geldig) en de grant link op de nieuwe hash. De contracten voor `profielservice` stonden al op een jaar.

### Waarneming voor moza-mock

Bij de 500 blijft de poging op `GEPLAND` zonder `notify_id`. Na vijf minuten vraagt de reconciliatie
van de NMC NotifyNL op `reference`. De mock antwoordt op élke reference "gevonden, nog sending", dus
zet de NMC de poging op `VERZONDEN`, terwijl er nooit iets verzonden is. De echte NotifyNL geeft dan
een lege lijst. De mapping "NotifyNL zoeken op reference" zou een onbekende reference leeg moeten
beantwoorden.

## Buiten scope / vervolg

- De gedeelde omgeving en `fsc-fieldlab`: certificaten uit de certportal, adres, peer-ID en trust
  anchor van hun Directory. Daar vervangt hun Directory `fsc-demo`; de `FSCInstallation`s veranderen
  alleen in `groupID`, `directory.external` en de Secrets.
- `FSCInstallation` als optionele template in de charts.
- De certificaten in `fsc-demo` worden door cert-manager vernieuwd (na 60 dagen; ze zijn 90 dagen
  geldig), maar de kopieën niet.
- NotifyNL via FSC.
