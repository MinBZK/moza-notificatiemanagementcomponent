# NMC op Fundament

De NMC draait op [Fundament](https://docs.fundament-poc.nl) als gewone workload in een namespace,
met een eigen PostgreSQL uit de CloudNativePG-plug-in. De externe diensten (Profielservice, NotifyNL)
komen uit [moza-mock](https://github.com/MinBZK/moza-mock). FSC tussen NMC en mock is de volgende stap
en staat hier nog niet in.

Issue: MinBZK/MijnOverheidZakelijk#1637. Plan: `docs/plans/2026-11-19-nmc-op-fundament.md`.

```text
namespace moza-mock                namespace nmc
┌──────────────────────┐           ┌───────────────────────────────┐
│ moza-mock (WireMock) │◄──────────┤ nmc ──► nmc-db (CNPG, PG 18)  │
│  /api/profielservice │   HTTP    │                               │
│  /notifynl/lokaal/…  ├──────────►│ /api/nmc/v1/notifynl-callback │
└──────────────────────┘  receipt  └───────────────────────────────┘
```

Twee namespaces, omdat OpenFSC één `FSCInstallation` per namespace toestaat en NMC en mock in de
volgende stap elk een eigen FSC-peer worden.

## Lokaal: de Fundament-sandbox

Voorwaarde: het lokale platform van Fundament met zijn plugin-sandbox (`k3d-fundament-plugin`), met de
plug-ins cert-manager en CloudNativePG; zie de
[ontwikkeldocumentatie van Fundament](https://github.com/fundament-oss/fundament/blob/master/docs/developer/plugins/testing-plugins-locally.md).
Alle commando's hieronder met `--context k3d-fundament-plugin` (of `--kube-context` bij helm).

### 1. Geheimen

De charts maken geen Secrets aan. Het bearer token van de NotifyNL-callback staat in beide namespaces
en moet gelijk zijn, anders weigert de NMC de receipt met 401 en blijft een notificatie op `SENDING`.

```bash
kubectl create namespace moza-mock
kubectl create namespace nmc

TOKEN=$(openssl rand -hex 32)
uuid() { cat /proc/sys/kernel/random/uuid; }
(umask 077; openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out /tmp/webhook.pem)   # PKCS#8-PEM, alleen voor jou leesbaar

kubectl -n moza-mock create secret generic moza-mock-admin \
  --from-literal=gebruiker=admin --from-literal=wachtwoord="$(openssl rand -hex 16)"
kubectl -n moza-mock create secret generic moza-mock-notifynl --from-literal=callback-token="$TOKEN"

kubectl -n nmc create secret generic nmc-secrets \
  --from-literal=NOTIFY_API_KEY="fundament-lokaal-$(uuid)-$(uuid)" \
  --from-literal=NOTIFY_CALLBACK_BEARER_TOKEN="$TOKEN" \
  --from-literal=HASH_PEPPER="$(openssl rand -hex 32)" \
  --from-literal=NMC_KEK_VERSIE_1="$(openssl rand -base64 32)" \
  --from-file=NMC_WEBHOOK_JWT_PRIVATE_KEY=/tmp/webhook.pem
rm /tmp/webhook.pem
```

- `NOTIFY_API_KEY` heeft de vorm van een NotifyNL-key (`<naam>-<service-id>-<secret>`, twee UUID's
  aan het eind); de mock controleert de handtekening niet.
- `--from-literal` met `$(...)` zet geen newline in de waarde. `openssl rand > bestand` plus
  `--from-file` wel, en dan verschillen de tokens aan beide kanten.

### 2. Installeren

```bash
helm -n moza-mock install moza-mock <moza-mock>/chart/moza-mock -f <moza-mock>/chart/moza-mock/values-lokaal.yaml
helm -n nmc install nmc chart/nmc -f chart/nmc/values-lokaal.yaml
```

Bij een nieuwe installatie start de NMC eerder dan zijn database. Flyway probeert dan tot 30 keer
met 5 seconden ertussen (`QUARKUS_FLYWAY_CONNECT_RETRIES`), en migreert daarna tot en met de laatste
`V*.sql`. Ontbreekt het Secret `nmc-db-app` nog, dan staat de pod even in `CreateContainerConfigError`;
dat lost zichzelf op.

`helm uninstall nmc` laat het CNPG-`Cluster` en zijn PVC staan (`helm.sh/resource-policy: keep`):
daarin staan de notificaties en het afleverbewijs. Weggooien gaat bewust met
`kubectl -n nmc delete cluster nmc-db`.

### 3. Controleren

```bash
kubectl -n nmc port-forward svc/nmc 8080:8080 &
kubectl -n moza-mock port-forward svc/moza-mock 8081:8080 &

curl -s localhost:8080/q/health/ready
curl -s localhost:8080/api/nmc/v1/.well-known/jwks.json          # toont kid fundament-lokaal-1
curl -s -X POST localhost:8080/api/nmc/v1/centraal/notificaties -H 'Content-Type: application/json' \
  -d '{"identificatieType":"KVK","identificatieNummer":"68750110","dienstverlener":"RVO","dienst":"eLoket","berichtType":"Demo template","berichtgegevens":{"naam":"Test BV Donald"}}'
curl -s -u admin:<wachtwoord> localhost:8081/__admin/requests   # Profielservice- en NotifyNL-aanroepen
```

Of met Bruno: de collectie van moza-mock, request `nmc/1-centrale-notificatie`, environment `lokaal`.

## Wat de chart verwacht

| Waarde | Waarvoor |
|---|---|
| `image.digest` | Verplicht. Een tag wordt bij een gelijke naam niet opnieuw gepulld |
| `profielservice.url`, `notify.url` | Verplicht. Bij de mock is het target in `…/notifynl/<target>` de bestemming van de receipt |
| `webhook.jwtKeyId`, `feed.source` | Verplicht; `feed.source` volgens `urn:nld:oin:<20 cijfers>:systeem:<naam>` |
| `postgres.*` | Een eigen CNPG-`Cluster` (1 instantie, `imageCatalogRef` naar `fundament-postgresql`); V16 vraagt PostgreSQL 17 of hoger |
| `kek.huidigeVersie`, `kek.versies` | De huidige KEK-versie en alle versies die de NMC moet kunnen lezen. Bij een rotatie blijft de oude versie in `versies` (en in het Secret) staan tot er geen notificaties meer onder gewrapt zijn |
| Secret `nmc-secrets` | `NOTIFY_API_KEY`, `NOTIFY_CALLBACK_BEARER_TOKEN`, `HASH_PEPPER`, `NMC_KEK_VERSIE_<n>` per versie, `NMC_WEBHOOK_JWT_PRIVATE_KEY` |

De overige configuratie en wat elke env-var doet, staat in `docs/zad-deploy.md`. Eén replica: Flyway
migreert bij het starten.

## Lokaal tegenover de gedeelde omgeving

Lokaal wijkt af van `fundament-poc.nl`:

- **Plug-ins** publiceer en keur je lokaal zelf goed; op de gedeelde omgeving installeer je ze uit
  de catalogus in de console, per cluster (dus gedeeld met wie dat cluster gebruikt).
- **Gateway API-CRD's** komen lokaal uit de Envoy Gateway-plug-in, alleen omdat de Istio-plug-in ze
  vraagt. Op de gedeelde omgeving is de gateway Istio.
- **OpenFSC** draait lokaal als losse operator (`openfsc-operator/chart` uit de Fundament-repo), niet
  als plug-in. De OpenFSC-plug-in installeert zelf CloudNativePG (release `cnpg`) en cert-manager
  (zelfde releasenaam als de cert-manager-plug-in). Naast de CNPG-plug-in faalt hij, en elke herstart
  zette cert-manager terug van 1.21.3 naar 1.17.2. Fundament noemt OpenFSC en CNPG wederzijds
  uitsluitend tot digilab miscellaneous#1289 is opgelost.
- **Opslag** is `local-path` in plaats van Ceph; certificaten komen uit een lokale CA (mkcert).
- **Namespaces** maak je lokaal zelf aan; een lokaal project heeft geen echte namespaces.
- In WSL moeten de `*.fundament.localhost`-namen in `/etc/hosts` staan: Go-tools als `functl`
  lossen `.localhost` niet zelf op.

## Open vragen voor de gedeelde omgeving

- Is digilab miscellaneous#1289 opgelost in de OpenFSC-versie in de catalogus? Zo niet: OpenFSC niet
  naast de CNPG-plug-in installeren.
- Staan cert-manager en de Gateway API-CRD's op het cluster (via Haven+)?
- Welke Pod Security-level en storageClass hebben de namespaces van een project?
- Mag een projectlid een CNPG-`Cluster` aanmaken en bijwerken? De CNPG-plug-in geeft projectbeheerders
  geen `update`; een `helm upgrade` die het `Cluster` wijzigt, vraagt dan een organisatiebeheerder.
- Is er op het fieldlab een gedeelde FSC-groep met een Directory, of draaien we zelf een Directory?

## Bekende beperkingen

- De webhook naar een dienstverlener en een `callbackUrl` naar een adres binnen het cluster gaan niet:
  `CallbackUrlValidator` weigert `*.svc` en namen zonder punt. Daarvoor is een hostname via de Gateway
  nodig.
- Geen NetworkPolicies, PodDisruptionBudget of HTTPRoute in de chart.
