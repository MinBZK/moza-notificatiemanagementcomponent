# Plan: NMC op een lokale Fundament-sandbox (fase 1, zonder FSC)

**Status:** Uitgevoerd (fase 1)

Issue: MinBZK/MijnOverheidZakelijk#1637.

## Context

Voor het fieldlab soevereine basis (23-25 november 2026) moet de NMC op Fundament draaien, als
workload in een namespace (geen plug-in). Dit plan dekt alleen de lokale Fundament-sandbox
(`k3d-fundament-plugin`, met cert-manager, CloudNativePG, Envoy Gateway voor de CRD's, Istio en de
openfsc-operator). De gedeelde omgeving (`fundament-poc.nl`) valt erbuiten; dit plan en
`docs/fundament-deploy.md` zijn daar wel de basis voor.

De externe diensten (Profielservice, NotifyNL) komen uit moza-mock. Geen van beide repo's had
Kubernetes-manifests. De images komen op digest uit GHCR; er wordt hier niets gebouwd.

- **Fase 1 (dit plan):** NMC met eigen PostgreSQL in namespace `nmc`, moza-mock in namespace
  `moza-mock`, over gewoon HTTP.
- **Fase 2 (eigen plan):** FSC tussen de twee namespaces. Eén `FSCInstallation` per namespace, en NMC
  en mock worden verschillende peers; daarom nu al twee namespaces.

## Keuzes

- **Een Helm-chart per repo**, klein en op zichzelf, zonder de Bitnami-`common`-subchart uit
  moza-poc-fbs-berichtenbox#444. Wel overgenomen: de probes op `/q/health/{live,ready,started}`, een
  restricted securityContext en een `/tmp`-emptyDir.
- **Gebaseerd op #66** (`1150-event-driven-core`): branch `1637-nmc-op-fundament` daarvan af, image
  `pr-66` op digest. Na de merge van #66: rebasen op `main`, image naar `main-<sha>`.
- **moza-mock** krijgt een eigen chart in de eigen repo (branch `1637-helm-chart`): hij is een aparte
  component die ook andere teams gebruiken.
- **Geen geheimen in git.** De charts verwijzen naar Secrets; aanmaken is een gedocumenteerde stap met
  `--from-literal` / `--from-file`, zonder newline in de waarde.
- **Database via CloudNativePG**: de chart maakt een `Cluster` met één instantie, PostgreSQL 18 via de
  ClusterImageCatalog `fundament-postgresql` van de plug-in. Een eigen instantie, zoals #66 vraagt.
  De datasource komt uit het Secret `nmc-db-app` dat CNPG aanmaakt.
- **Flyway bij het starten** (`QUARKUS_FLYWAY_MIGRATE_AT_START=true`, zoals op ZAD), dus één replica.

## Stappen

1. moza-mock: `chart/moza-mock/` (Deployment, Service, `values-lokaal.yaml`) en een README-sectie.
   Admin-API achter basic auth uit een Secret; `WIREMOCK_NOTIFYNL_LOKAAL_URL` en het callback-token
   voor de receipt naar de NMC. TCP-probes, want `/__admin/health` geeft 401. Non-root UID, omdat het
   image geen `USER` heeft.
2. NMC: `chart/nmc/` (Deployment, Service, CNPG-`Cluster`, `values-lokaal.yaml`) en
   `docs/fundament-deploy.md`. Alle env-vars uit `docs/zad-deploy.md`; de verplichte waarden falen bij
   het renderen met een melding.
3. Lokaal uitrollen volgens `docs/fundament-deploy.md`. De gebruiker draait de commando's.

## Buiten scope / vervolg

- De gedeelde omgeving, en fase 2 (FSC). De NMC stuurt nog geen `Fsc-Grant-Hash`; diensten en
  contracten gaan via de FSC Controller.
- Webhook of `callbackUrl` naar moza-mock: `CallbackUrlValidator` weigert `*.svc` en namen zonder punt,
  dus dat vraagt een hostname via de Gateway.
- HTTPRoute, NetworkPolicies, PodDisruptionBudget, meer replica's (Flyway dan als Job), en
  `helm lint`/`helm template` in CI.

## Verificatie

1. `helm lint` en `helm template -f values-lokaal.yaml` in beide repo's zonder fouten; zonder digest of
   verplichte URL faalt het renderen met een melding. Een server-side dry-run tegen de sandbox slaagt.
2. Na het uitrollen: pods Ready, CNPG-`Cluster` healthy, de NMC-log toont de Flyway-migraties tot en met V16.
3. `/q/health/ready` geeft 200; `/api/nmc/v1/.well-known/jwks.json` toont de `kid`.
4. `POST /api/nmc/v1/centraal/notificaties` (Bruno `nmc/1-centrale-notificatie`) geeft 200; het journal
   van de mock toont de aanroepen naar Profielservice en NotifyNL, en de receipt-callback komt bij de
   NMC aan.
5. Non-happy: de vaste BSN's van de Profielservice-mock (404, 500, geen voorkeur) eindigen in de
   verwachte status; zonder `nmc-secrets` of met een te korte KEK wordt de pod niet Ready.

## Uitkomst

Op 19 november 2026 lokaal uitgerold, met NMC-image `pr-66` (`sha256:95445e80…`) en moza-mock `latest`
(`sha256:1ae1191b…`).

- Flyway paste 15 migraties toe, het schema staat op v16. `/q/health/ready` geeft `UP` met de
  databasecheck, en `/.well-known/jwks.json` toont de `kid`.
- Een centrale notificatie (KVK 68750110) geeft **202**. Het journal van de mock toont
  `POST /api/profielservice/v1/partij` (200) en `POST /notifynl/lokaal/v2/notifications/email` (201).
  De receipt kwam met het juiste token terug: de notificatie staat op `BEZORGD`.
- Non-happy, via de vaste BSN's in de Profielservice-mock (de aanname geeft ook hier 202; de fout
  volgt asynchroon in de status):

  | BSN | Mock | Uitkomst in de NMC |
  |---|---|---|
  | 999996915 | 404 | `NIET_BEZORGBAAR`, reden `GEEN_CONTACTGEGEVENS`, `WISSEN`-taak over 7 dagen |
  | 999991401 | 500 | `IN_VERZENDING`; de verzendtaak wordt uitgesteld en later opnieuw geprobeerd |
  | 111222333 | 200 zonder voorkeur | `NIET_BEZORGBAAR`, reden `GEEN_CONTACTGEGEVENS` |

  Elk ander nummer valt op de catch-all van de mock en levert "Test BV Donald" op, dus `BEZORGD`.
- Niet getest: de pod zonder `nmc-secrets` of met een te korte KEK. Wel bij het renderen: zonder
  digest, URL's of een geldige KEK-versie faalt `helm template` met een melding.
- Bij de eerste installatie crashte de NMC twee keer: hij was eerder klaar dan zijn database. Opgelost
  met `QUARKUS_FLYWAY_CONNECT_RETRIES`; alleen bij een verse installatie live te zien.

### Na de review

- **Hoog:** de chart gaf alleen de KEK van de huidige versie mee, waardoor een rotatie de oude
  notificaties onleesbaar zou maken. Nu geeft `kek.versies` alle versies mee, en het renderen faalt
  als `huidigeVersie` daar niet in staat.
- **Hoog:** `helm uninstall` verwijderde het CNPG-`Cluster`, en daarmee de PVC met het afleverbewijs.
  Nu staat er `helm.sh/resource-policy: keep` op.
- **Laag:** de webhook-sleutel wordt nu met `umask 077` aangemaakt, en de documentatie over de start
  is gecorrigeerd.
- **Vervolg** (niet in deze wijziging): NetworkPolicies en authenticatie vóór de gedeelde omgeving,
  `sslmode=verify-full` naar de database. `quarkus.tls.notify.trust-all=true` in
  `application.properties` controleert TLS naar NotifyNL nooit; dat is iets voor de NMC zelf.

### Waarnemingen voor de NMC

- Bij `NIET_BEZORGBAAR` blijft de `poging` op `GEPLAND` staan, terwijl de notificatie al een eindstatus heeft.
- Bij een centraal profiel stopt de NMC op `NIET_BEZORGBAAR`; contactherstel (terugvallen op een brief)
  zit er nog niet in.
