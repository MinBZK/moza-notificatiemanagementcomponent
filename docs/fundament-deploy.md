# NMC op Fundament

De NMC draait op [Fundament](https://docs.fundament-poc.nl) als gewone workload in een namespace,
met een eigen PostgreSQL uit CloudNativePG. De externe diensten (Profielservice, NotifyNL) komen uit
[moza-mock](https://github.com/MinBZK/moza-mock). De Profielservice kan ook via FSC lopen; zie
[FSC](#fsc-lokaal). Voor het fieldlab: [De gedeelde omgeving](#de-gedeelde-omgeving-fieldlab).

Issue: MinBZK/MijnOverheidZakelijk#1637. Plannen: `docs/plans/2026-11-19-nmc-op-fundament.md` (fase 1)
en `docs/plans/2026-11-20-nmc-fsc-op-fundament.md` (fase 2, FSC).

```text
namespace moza-mock                namespace nmc
┌──────────────────────┐           ┌───────────────────────────────┐
│ moza-mock (WireMock) │◄──────────┤ nmc ──► nmc-db (CNPG, PG 18)  │
│  /api/profielservice │   HTTP    │                               │
│  /notifynl/lokaal/…  ├──────────►│ /api/nmc/v1/notifynl-callback │
└──────────────────────┘  receipt  └───────────────────────────────┘
```

Twee namespaces, omdat OpenFSC één `FSCInstallation` per namespace toestaat en NMC en mock elk een
eigen FSC-peer zijn.

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

## FSC (lokaal)

De NMC belt de Profielservice via zijn eigen outway; moza-mock biedt hem aan via zijn eigen inway.
Op het fieldlab beheert een ander de Directory en de certportal (groep `fsc-fieldlab`). Lokaal
speelt de sample-installatie `fsc-demo` van de openfsc-operator die rol (Self mode); NMC en mock doen
mee in External mode.

```text
fsc-demo   (Self)      Directory + groeps-CA (Issuer fsc-group)   peer 12345678901234567899
nmc        (External)  inway + outway default                     peer 11111111111111111111
moza-mock  (External)  inway default → moza-mock:8080             peer 22222222222222222222

dienstverlener ──► outway fsc-demo ══ mTLS ══► inway nmc ──► nmc
nmc ──► outway nmc ══ mTLS ══► inway moza-mock ──► moza-mock (Profielservice)
```

De peer-ID's zijn placeholders; er is nog geen eigen OIN.

### 1. Certificaten

Elke peer heeft een groepscertificaat voor de Manager en een voor elke gateway, met de peer-ID als
`serialNumber` en de servicenamen als SAN (anders start de inway niet). Lokaal tekent de Issuer
`fsc-group` van `fsc-demo` ze; op het fieldlab de certportal. Per peer:

```yaml
apiVersion: cert-manager.io/v1
kind: Certificate
metadata:
  name: nmc-manager-group               # en nmc-outway-default-group, moza-mock-manager-group,
  namespace: fsc-demo                   #    moza-mock-inway-default-group
spec:
  secretName: nmc-manager-group-tls
  commonName: fsc-open-fsc-manager-external.nmc
  dnsNames:                             # gateway: fsc-outway-default.nmc / fsc-inway-default.moza-mock
    - fsc-open-fsc-manager-external
    - fsc-open-fsc-manager-external.nmc
    - fsc-open-fsc-manager-external.nmc.svc.cluster.local
  issuerRef: {kind: Issuer, name: fsc-group}
  subject:
    organizations: [nmc]
    serialNumber: "11111111111111111111"
```

Daarna kopiëren naar de peer-namespace, als `kubernetes.io/tls`, en een trust anchor met alleen het
CA-certificaat (niet de sleutel). De sleutels gaan via een pipe, niet via schijf:

```bash
K="kubectl --context k3d-fundament-plugin"
kopieer() { # bron-secret doel-namespace doel-naam
  $K -n fsc-demo get secret "$1" -o json \
    | jq --arg n "$3" '{apiVersion:"v1",kind:"Secret",type:"kubernetes.io/tls",metadata:{name:$n},data:{"tls.crt":.data["tls.crt"],"tls.key":.data["tls.key"]}}' \
    | $K -n "$2" apply -f -
}
kopieer nmc-manager-group-tls             nmc       fsc-manager-group-tls
kopieer nmc-outway-default-group-tls      nmc       fsc-outway-default-group-tls
kopieer moza-mock-manager-group-tls       moza-mock fsc-manager-group-tls
kopieer moza-mock-inway-default-group-tls moza-mock fsc-inway-default-group-tls

for ns in nmc moza-mock; do
  $K -n fsc-demo get secret fsc-group-ca -o json \
    | jq '{apiVersion:"v1",kind:"Secret",type:"Opaque",metadata:{name:"fsc-group-trust"},data:{"ca.crt":.data["tls.crt"]}}' \
    | $K -n "$ns" apply -f -
done
```

### 2. Peers

```yaml
apiVersion: openfsc.fundament.io/v1
kind: FSCInstallation
metadata:
  name: fsc
  namespace: nmc                        # moza-mock: peerID 2222…, `inways` met fsc-inway-default-group-tls
spec:
  groupID: fsc-demo
  peerID: "11111111111111111111"
  directory:
    mode: External
    external:
      address: https://fsc-open-fsc-manager-external.fsc-demo:8443
      peerID: "12345678901234567899"
      trustAnchor: {name: fsc-group-trust}   # sleutel ca.crt
  certificate:
    existingSecret: fsc-manager-group-tls
  outways:
    - name: default
      certificate: {existingSecret: fsc-outway-default-group-tls}
  postgres:
    image: ghcr.io/cloudnative-pg/postgresql:16
    instances: 1
    storageClass: local-path
    storageSize: 1Gi
```

De operator zet per peer een eigen FSC-database (`fsc-postgresql`, los van `nmc-db`), Manager,
Controller, audit- en transactielog en de gateway neer. Klaar als `kubectl get fscinstallation -A`
voor beide `Active` toont. Een inway die één keer herstart, was er eerder dan zijn Controller.

### 3. Service en contracten

Via de FSC Controller van elke peer (zonder login):

```bash
kubectl -n nmc port-forward svc/fsc-open-fsc-controller 8083:80 &
kubectl -n moza-mock port-forward svc/fsc-open-fsc-controller 8084:80 &
```

1. **moza-mock (8084):** Services → `profielservice` → `http://moza-mock.moza-mock.svc:8080`, inway
   `default`. Daarna een ServicePublication naar de Directory; die tekent hem automatisch.
2. **nmc (8083):** Directories → `profielservice` van moza-mock → outway `default` → **Toegang
   aanvragen**. Niet via "Contracten → nieuw": daar staat moza-mock pas in de keuzelijst als de Manager
   van de NMC hem kent. De verbinding staat dan op "Voorgesteld".
3. **moza-mock (8084):** Binnenkomende verbindingen → de aanvraag van `nmc` tekenen.
4. **nmc (8083):** In- & Outways → `default` → grant link: pad `profielservice` → de grant van de
   verbinding.

**Geldigheid:** het formulier van een contract staat standaard op twee dagen. Daarna weigert de
outway met `UNKNOWN_GRANT_HASH_IN_HEADER`, ook via een grant link, en toont hij in de foutmelding een
lege lijst `services`/`grant_links`. Zet de geldigheid bij publicatie én verbinding zelf ruim (lokaal
een jaar). Een verlopen contract verleng je niet: maak een nieuwe publicatie en verbinding, en zet de
grant link op de nieuwe `$1$3$`-hash. Dat is de hash die begint met **`$1$3$`**; die met `$1$2$` is de publicatie en geeft
   `UNKNOWN_GRANT_HASH_IN_HEADER`.

Door de grant link stuurt de outway alles onder `/profielservice/` met die grant door, en hoeft de NMC
geen `Fsc-Grant-Hash` mee te sturen.

### 4. De NMC via FSC

```bash
helm -n nmc upgrade nmc chart/nmc -f chart/nmc/values-lokaal.yaml -f chart/nmc/values-lokaal-fsc.yaml
```

Controleren: de logs van `deploy/fsc-outway-default` (nmc) tonen `forwarding API request` met
`destination-peer-id` 2222… en de grant; die van `deploy/fsc-inway-default` (moza-mock)
`proxying request to API`. Zonder `-f values-lokaal-fsc.yaml` belt de NMC de mock weer direct.

### 5. De NMC aanbieden via een inway

Een dienstverlener belt de NMC via zijn eigen outway en de inway van de NMC. Lokaal speelt de outway
van `fsc-demo` de dienstverlener.

1. Een certificaat `nmc-inway-default-group` zoals in stap 1 (SAN `fsc-inway-default.nmc`, peer 1111…),
   gekopieerd naar `nmc` als `fsc-inway-default-group-tls`.
2. De inway toevoegen aan de peer:
   ```bash
   kubectl -n nmc patch fscinstallation fsc --type merge \
     -p '{"spec":{"inways":[{"name":"default","certificate":{"existingSecret":"fsc-inway-default-group-tls"}}]}}'
   ```
3. **nmc (8083):** service `nmc` → `http://nmc.nmc.svc:8080` op inway `default`, en een publicatie.
4. **fsc-demo (8082):** Directories → `nmc` → Toegang aanvragen; **nmc (8083):** de verbinding
   tekenen; **fsc-demo (8082):** grant link `nmc` op de **outway** (niet de inway), met de nieuwe
   `$1$3$`-grant. Er staat er al een voor `profielservice`: kies die met service `nmc`.
5. Testen vanuit `fsc-demo`:
   ```bash
   kubectl -n fsc-demo run rvo-test --rm -i --restart=Never --image=curlimages/curl -- \
     curl -s -X POST http://fsc-outway-default/nmc/api/nmc/v1/centraal/notificaties \
     -H 'Content-Type: application/json' -d '{"identificatieType":"KVK","identificatieNummer":"68750110",…}'
   ```
   De inway van de NMC logt `proxying request to API`; de notificatie gaat daarna via de outway van de
   NMC naar de Profielservice.

De receipt-callback van de NotifyNL-mock en de webhooks naar dienstverleners blijven direct.

## Lokaal stoppen en weer starten

In `~/dev/fundament`, in WSL:

```bash
mise exec -- k3d cluster stop fundament fundament-plugin                 # stoppen (niet met docker stop)
docker start registry.localhost plugin-registry.localhost
mise exec -- k3d cluster start fundament fundament-plugin                # weer starten
```

- Faalt het starten met `error waiting for log line … status=restarting`, dan herstartte de node één
  keer; start daarna `fundament-plugin` apart. Geeft `kubectl --context k3d-fundament` daarna
  `x509: certificate signed by unknown authority`, dan wijst de load balancer nog naar het oude IP van
  de node: `docker restart k3d-fundament-serverlb`.
- Port-forwards stoppen bij het stoppen; start ze opnieuw.
- Controleer daarna één notificatie via FSC. Een 400 `UNKNOWN_GRANT_HASH_IN_HEADER` betekent meestal
  een verlopen contract (zie "Geldigheid" onder FSC).

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

## De gedeelde omgeving (fieldlab)

Op `fundament-poc.nl`, organisatie `fieldlab-c`, cluster `cluster-c` (één node, gedeeld met de andere
projecten van het fieldlab), project `notificatiedienst`. Het FBS zit in een eigen project
(`federatiefberichtenstelsel`) op hetzelfde cluster.

```text
project notificatiedienst
├─ nmc                  NMC + nmc-db (CNPG, PG 18); FSC-peer nmc (inway + outway)
├─ mock-profielservice  moza-mock, als Profielservice; FSC-peer profielservice-mock (inway)
└─ mock-notifynl        moza-mock, als NotifyNL; geen FSC (ook de echte NotifyNL is een publieke API)
```

Een namespace `X` van het project heet in Kubernetes **`tnt-notificatiedienst--X`**. De mocks zijn
twee installaties van dezelfde chart; elk gebruikt alleen zijn eigen deel van de mappings.

### Toegang

`functl` met een API-key uit de console, in een eigen configmap, zodat een lokale sandbox-login
blijft werken. Gebruik het binaire `functl` (de kubeconfig roept het aan), niet `just functl`.

```bash
curl -fsSL https://github.com/fundament-oss/fundament/releases/download/functl-latest/functl_linux_amd64.tar.gz \
  | tar -xzf - -C ~/.local/bin functl
export FUNCTL_CONFIG_DIR=$HOME/.config/fundament-poc
functl auth login                                   # API-key uit de console
functl org list; functl cluster list --org=<ORG_ID>
functl cluster kubeconfig <CLUSTER_ID> --org=<ORG_ID> > kubeconfig
export KUBECONFIG=$PWD/kubeconfig
```

Het exec-blok in die kubeconfig roept `functl cluster token` aan. Zet daar `FUNCTL_CONFIG_DIR` in
(`env:`) en het volledige pad naar `functl`, anders gebruikt `kubectl` de lokale login.

### Installeren

```bash
P=<PROJECT_ID>; NS=tnt-notificatiedienst
for n in nmc mock-profielservice mock-notifynl; do functl namespace create --project=$P $n --org=<ORG_ID>; done
```

De Secrets zoals bij [Lokaal](#1-geheimen), maar per namespace: `moza-mock-admin` in beide mocks,
`moza-mock-notifynl` in `mock-notifynl` (hetzelfde token als `NOTIFY_CALLBACK_BEARER_TOKEN` in
`nmc-secrets`) en in `mock-profielservice` (een willekeurig token; de chart vraagt het Secret, die mock
gebruikt het niet). Daarna:

```bash
for m in mock-profielservice mock-notifynl; do
  helm -n $NS--$m install moza-mock <moza-mock>/chart/moza-mock -f <moza-mock>/chart/moza-mock/values-fieldlab.yaml
done
helm -n $NS--nmc install nmc chart/nmc -f chart/nmc/values-fieldlab.yaml
```

Controleren zoals bij [Lokaal](#3-controleren), met `-n $NS--nmc`. Op 23 november 2026 gaven de
centrale notificatie en de vaste BSN's dezelfde uitkomst als lokaal.

### Wat er op het cluster staat

- **CloudNativePG** komt van Haven+ (Flux, release `cloudnative-pg` 0.29.0, operator 1.30.0), met de
  catalogus `postgresql-standard-trixie` (PG 15 tot en met 18). De CNPG-plug-in staat op `Failed`,
  omdat hij die operator niet overneemt; we hebben hem niet nodig.
- **cert-manager** v1.21.0, ook van Haven+ via Flux, release `cert-manager`.
- **Opslag:** `ceph-incluster` (default).
- **Kyverno** dwingt het restricted-profiel af (non-root, geen privilege escalation, seccomp, alleen
  veilige volumetypes). Beide charts voldoen.
- Geen ResourceQuota, LimitRange of NetworkPolicies in de namespaces van het project.
- Een projectlid heeft hier ruime rechten (ook buiten het project): blijf in de eigen namespaces.
- **Geen OpenFSC.** De OpenFSC-plug-in installeert zelf cert-manager v1.17.2 onder dezelfde
  releasenaam (`cert-manager`) en een tweede CNPG (release `cnpg`). Hier zou dat cert-manager voor
  alle teams terugzetten, en Flux zet hem daarna weer terug: niet installeren zolang
  miscellaneous#1289 open staat. Alleen de openfsc-operator zou genoeg zijn.

### FSC op het fieldlab: het MOZa FSC-testnet

De OpenFSC-plug-in en de Directory van `fsc-fieldlab` waren er op 24 november nog niet. Daarom de
terugvaloptie uit #1661: eigen peers op Fundament, aangesloten op het **MOZa FSC-testnet**
([MinBZK/moza-fsc-testnet](https://github.com/MinBZK/moza-fsc-testnet), groep `moza-fbs-test`). De
Directory en de test-CA van het testnet blijven op ZAD; alleen onze peers draaien hier.

| Peer | OIN (peer-ID) | Namespace | Prefix | Componenten |
|---|---|---|---|---|
| `nmc` | `00000000000000000050` | `tnt-notificatiedienst--nmc` | `nmc` | manager, controller, txlog, inway, outway |
| `profielservice-mock` | `00000000000000000060` | `tnt-notificatiedienst--mock-profielservice` | `ps` | manager, controller, txlog, inway |

- **Chart `chart/fsc-peer`:** de stock-images van OpenFSC v2.9.0 (de versie van het testnet), zonder
  operator. Per component een Deployment en Service `<prefix>-fsc<component>`, een CNPG-database
  `<prefix>-fscpg` (schema's `manager` en `txlog`, `helm.sh/resource-policy: keep`) en de migraties in
  een init-container. `runAsUser: 1001` voor Kyverno.
- **Publiek:** manager en inway via TLS-passthrough-listeners op de Gateway van de NMC
  (`gateway.passthrough` in `chart/nmc/values-fieldlab.yaml`, `TLSRoute`'s en een `DNSEndpoint` uit
  `chart/fsc-peer`), als `<prefix>-fscmgr` en `<prefix>-fscinway` op `.cluster-c.fieldlab2026.nl`.
  Passthrough, omdat FSC mTLS met de groepscertificaten doet. De outway is alleen intern:
  `http://nmc-fscoutway:8080`.
- **Twee ketens per component:** een groepscertificaat van de intermediate van het testnet (voor het
  verkeer tussen peers; met de OIN als `serialNumber`) en een intern certificaat van een eigen CA per
  peer (tussen de componenten van één peer). Beide in het Secret `fsc-pki`.

#### Een peer aansluiten

De stappen van `docs/peer-aansluiten.md` uit het testnet, met de scripts in `deploy/fsc-testnet/`:

```bash
T=~/dev/fieldlab/moza-fsc-testnet                 # checkout van het testnet
deploy/fsc-testnet/gen-csr.sh $T                  # interne CA + certs, group-sleutels + CSR's
deploy/fsc-testnet/teken.sh $T                    # alleen wie de intermediate mag ontsleutelen
# publieke certs (pki/internal/<peer>, pki/out/<peer>) via een PR in het testnet; sleutels nooit
deploy/fsc-testnet/pki-secret.sh $T nmc tnt-notificatiedienst--nmc
deploy/fsc-testnet/pki-secret.sh $T profielservice-mock tnt-notificatiedienst--mock-profielservice
helm -n tnt-notificatiedienst--nmc install nmc-fsc chart/fsc-peer -f chart/fsc-peer/values-fieldlab-nmc.yaml
helm -n tnt-notificatiedienst--mock-profielservice install ps-fsc chart/fsc-peer \
  -f chart/fsc-peer/values-fieldlab-profielservice-mock.yaml
helm -n tnt-notificatiedienst--nmc upgrade nmc chart/nmc -f chart/nmc/values-fieldlab.yaml   # passthrough
```

- Een nieuwe peer (zoals het FBS): een regel in `PEERS` van `gen-csr.sh` en `teken.sh`, een
  values-bestand voor `chart/fsc-peer`, en een passthrough-listener in `chart/nmc/values-fieldlab.yaml`
  (of een eigen Gateway).
- `teken.sh` haalt de intermediate-sleutel op met `pki/geheimen.sh` (SOPS + age; je age-sleutel moet in
  `pki/.sops.yaml` staan) en wist hem na afloop. Het tekent alleen een CSR met precies één
  `serialNumber` gelijk aan de OIN van de peer.
- Laat de checkout van het testnet op de branch van de PR tot die gemerged is: de getekende certs
  staan anders niet in de werkkopie.

#### Services en contracten

In de Controller van een peer (`kubectl port-forward svc/<prefix>-fscctl 8085:8080`; zonder login, zie
[Bekende beperkingen](#bekende-beperkingen)):

1. **profielservice-mock:** service `profielservice` → `http://moza-mock.tnt-notificatiedienst--mock-profielservice.svc:8080`
   op inway `ps-inway`, en een publicatie (`$1$2$…`) naar de Directory (`00000000000000000010`), die
   hem zelf tekent.
2. **nmc:** een service connection (`$1$3$…`) naar `00000000000000000060`/`profielservice` voor
   de outway; de mock accepteert hem in zijn Controller.
3. **nmc:** grant link `profielservice` op de outway, met de hash van de service connection (niet die
   van de publicatie). `profielservice.url` wordt `http://nmc-fscoutway:8080/profielservice`.
4. **nmc:** service `nmc` → `http://nmc.tnt-notificatiedienst--nmc.svc:8080` op inway `nmc-inway`,
   gepubliceerd naar de Directory, voor het FSC.

Controleren: de peers en services in de directory-ui van het testnet, en per aanroep `forwarding API
request` in de logs van `nmc-fscoutway` en `proxying request to API` in die van `ps-fscinway`, met
dezelfde `grant-hash`. Op 24 november 2026 kwam een centrale notificatie zo via FSC aan als e-mail
(via een tijdelijke persona in de mock); `999991401` (500) en `111222333` (geen contactgegevens) gaven
dezelfde uitkomst als zonder FSC.

Terug zonder FSC: `profielservice.url` op
`http://moza-mock.tnt-notificatiedienst--mock-profielservice.svc:8080`. De peers kunnen blijven staan.

De optionele `FSCInstallation` in beide charts (`fsc.enabled`, standaard uit) is voor als de
openfsc-operator en `fsc-fieldlab` er wel zijn; op het fieldlab gebruiken we hem niet.

### Publiek adres

Net als op ZAD is de NMC publiek bereikbaar, op **https://nmc.cluster-c.fieldlab2026.nl**, zodat een
dienstverlener-mock buiten Fundament hem kan aanroepen. De NMC heeft nog geen authenticatie: iedereen
kan dus notificaties aanbieden.

De chart maakt daarvoor met `gateway.enabled` een eigen Envoy Gateway (`className: eg`), net als het
FBS voor `fbs-magazijn.cluster-c.fieldlab2026.nl`: een Let's Encrypt-certificaat via HTTP-01, een
`HTTPRoute` voor `gateway.paths` (standaard alleen `/q/openapi` en `/q/swagger-ui`; op het fieldlab
`/`), en een `DNSEndpoint` voor external-dns. Het publieke IP kent de Gateway pas na het aanmaken, dus
dat gaat in twee stappen: installeren, `status.addresses` van de Gateway in `gateway.address` zetten,
nog een `helm upgrade`.

### NotifyNL

Op het fieldlab de echte NotifyNL van Worth (`https://api.notifynl.nl`), met een team-key: die bezorgt
alleen bij de adressen van het team, dus een centrale notificatie naar het adres van de
Profielservice-mock wordt geweigerd. De key zet je zelf in het Secret, zonder dat hij in een bestand of
de shellhistorie komt:

```bash
read -rs K
jq -n --arg k "$K" '{stringData:{NOTIFY_API_KEY:$k}}' \
  | kubectl -n tnt-notificatiedienst--nmc patch secret nmc-secrets --type merge --patch-file /dev/stdin
unset K
```

- De templates in `BerichtType` zijn die van de NotifyNL van het LPC. Worth heeft andere: zet ze met
  `NMC_BELEID_<BERICHTTYPE>_TEMPLATE_ID` (in `values-fieldlab.yaml` via `extraEnv`). Anders weigert
  NotifyNL met `400 Template not found`. Het template-ID komt bij de aanname op de notificatie, dus een
  wijziging geldt alleen voor nieuwe notificaties.
- De receipt-callback staat in het dashboard van Worth op de NMC van ZAD; deze NMC krijgt de afleverstatus
  door zelf na te vragen.
- Terug naar de mock: `notify.url` op `http://moza-mock.tnt-notificatiedienst--mock-notifynl.svc:8080/notifynl/lokaal`.

### Een nieuw image en Flyway

Het image `pr-<n>` bouwt GitHub uit de merge van de PR met zijn base. Zolang #66 niet gemerged is, kan
die base bestaande migraties nog aanpassen. Een nieuw image start dan niet: `Migration checksum
mismatch for migration version …`, terwijl de oude pod blijft draaien. Zolang er alleen testgegevens
in staan: database opnieuw aanmaken, niet `flyway repair` (het schema verschilt dan echt).

```bash
kubectl -n tnt-notificatiedienst--nmc delete cluster nmc-db
helm -n tnt-notificatiedienst--nmc upgrade nmc chart/nmc -f chart/nmc/values-fieldlab.yaml
kubectl -n tnt-notificatiedienst--nmc rollout restart deploy/nmc
```

Zo op 23 november 2026 bij de overstap van `pr-66` (19 november) naar `pr-109`.

### Aanroepen vanuit het FBS

Binnen het cluster, zonder FSC: `http://nmc.tnt-notificatiedienst--nmc.svc:8080/api/nmc/v1/…`. De
spec staat op `/q/openapi` (en `/q/swagger-ui/`). Met FSC blijven de paden gelijk en verandert alleen
het begin van de URL: `http://<outway van het FBS>/nmc/api/nmc/v1/…`, met een grant link `nmc`. De
NMC staat als service `nmc` van peer `00000000000000000050` in de Directory van het testnet; het FBS
heeft een eigen peer in die groep nodig en vraagt toegang aan, die de NMC in zijn Controller accepteert.

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

Beantwoord op 23 november (zie [hierboven](#wat-er-op-het-cluster-staat)): cert-manager en de Gateway
API-CRD's staan er, Kyverno dwingt restricted af, de storageClass is Ceph, en een projectlid mag een
CNPG-`Cluster` aanmaken en bijwerken. Nog open:

- Komt OpenFSC op `cluster-c` zonder de gedeelde cert-manager en CNPG te raken (alleen de operator,
  of een plug-in waarin miscellaneous#1289 is opgelost)? Het OpenFSC-team werkt de plug-in bij.
- FSC (`fsc-fieldlab`): waar komt de Directory, en wat zijn het adres, de peer-ID en de trust anchor?
  Hoe komen we via de certportal aan certificaten, en wat hebben ze van ons nodig (peer-ID/OIN)? Tot
  dan het MOZa FSC-testnet (zie [hierboven](#fsc-op-het-fieldlab-het-moza-fsc-testnet)).
- Na het fieldlab: de peers `…0050` en `…0060` afmelden bij het testnet, of afspreken dat ze blijven.

## Bekende beperkingen

- De webhook naar een dienstverlener en een `callbackUrl` naar een adres binnen het cluster gaan niet:
  `CallbackUrlValidator` weigert `*.svc` en namen zonder punt. Daarvoor is een hostname via de Gateway
  nodig.
- Geen NetworkPolicies, PodDisruptionBudget of HTTPRoute in de chart.
- NotifyNL loopt niet via FSC; alleen de Profielservice.
- De FSC Controller heeft geen login: wie de service kan bereiken, kan contracten tekenen. Op een
  gedeeld cluster hoort daar authenticatie voor; de operator stelt die nu niet in, en `chart/fsc-peer`
  ook niet (`AUTHN none`). Niet publiek, maar zonder NetworkPolicy wel vanuit elke pod op het
  cluster; dat geldt ook voor de outway (gewone HTTP op 8080), die dan namens onze peer aanroept.
- `chart/fsc-peer` draait één replica per component en zet `DISABLE_CRL_CHECKS=true`, zoals de runbook
  van het testnet voorschrijft. Een ingetrokken testcertificaat wordt dus niet geweigerd.
- De kopieën van de FSC-certificaten worden niet mee vernieuwd als cert-manager de originelen in
  `fsc-demo` vernieuwt (na 60 dagen; ze zijn 90 dagen geldig): dan opnieuw kopiëren.
