# Container-image lokaal testen (jib)

Dit gaat over de **container-image** (prod-profiel, zoals op ZAD). Voor gewoon
lokaal ontwikkelen in **dev-mode** (`./mvnw quarkus:dev`) — zie de sectie
"Lokaal draaien" in [`../README.md`](../README.md).

De image wordt met **jib** gebouwd (geen Dockerfile/container-daemon nodig voor de
push; wel een Podman-daemon voor het lokaal draaien). Drie niveaus, oplopend in
dekking.

## 1. Config-sanity (geen daemon)

```bash
./mvnw package -DskipTests
```

Bewijst dat `pom.xml` + de `quarkus.container-image.*` / `quarkus.jib.*` props
parsen. Bouwt geen image.

## 2. Echte image → lokale Podman daemon (aanbevolen)

jib bouwt naar de lokale daemon (`build=true`, geen `push`).
`docker-executable-name=podman` laat jib de Podman-binary gebruiken i.p.v. `docker`.

> **Vooraf**: de Podman-machine moet draaien (`podman machine start`) — anders vindt
> jib geen container-runtime en faalt de build met *"Cannot get an executable name
> when no container runtime is available"*.

```bash
./mvnw package -DskipTests \
  -Dquarkus.container-image.build=true \
  -Dquarkus.jib.docker-executable-name=podman \
  -Dquarkus.container-image.group=local \
  -Dquarkus.container-image.tag=test \
  -Dquarkus.swagger-ui.always-include=true
```

→ image `local/moza-notificatiemanagementcomponent:test`.
Digest-file check: `cat target/jib-image.digest`.

> Werkt de auto-detectie niet, wijs jib dan expliciet naar de Podman-socket via
> `DOCKER_HOST` i.p.v. de executable-flag:
> ```bash
> export DOCKER_HOST="unix://$(podman machine inspect --format '{{.ConnectionInfo.PodmanSocket.Path}}')"
> ```

Draaien met de Postgres uit `podman compose up -d` (zelfde compose als in de
README; de `nmc/nmc`-creds hieronder komen daarvandaan):

```bash
podman run -d --rm --name nmc -p 8080:8080 \
  -e QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://host.containers.internal:5432/nmc \
  -e DB_USERNAME=nmc -e DB_PASSWORD=nmc \
  -e QUARKUS_FLYWAY_MIGRATE_AT_START=true \
  local/moza-notificatiemanagementcomponent:test
```

`-d` draait detached; `--name nmc` geeft een vaste handle. Logs volgen / stoppen:

```bash
podman logs -f nmc   # boot volgen
podman stop nmc      # stoppen (--rm ruimt 'm meteen op)
```

> `host.containers.internal` = het Podman-alias voor de host (rootless canoniek).
> Op Docker heet dit `host.docker.internal`.

> ⚠️ `QUARKUS_FLYWAY_MIGRATE_AT_START=true` is nodig: de image draait het
> **prod-profiel**, daar staat migrate default uit én `schema-management=validate`
> — zonder migratie faalt de boot op een lege DB.

Verifiëren:

```bash
curl localhost:8080/q/health/ready   # UP, incl. datasource-check
curl localhost:8080/q/swagger-ui     # Swagger UI mee (swagger-ui.always-include)
```

## 3. Daemon-loze push → CI-pariteit

Test exact wat de workflow doet (jib pusht direct naar de registry, geen daemon).
Naar je eigen GHCR-namespace met een PAT (`write:packages`):

```bash
./mvnw package -DskipTests \
  -Dquarkus.container-image.build=true -Dquarkus.container-image.push=true \
  -Dquarkus.container-image.registry=ghcr.io \
  -Dquarkus.container-image.group=<jouw-gh-user> \
  -Dquarkus.container-image.tag=test \
  -Dquarkus.container-image.username=<jouw-gh-user> \
  -Dquarkus.container-image.password=<PAT>
```

Het enige dat CI hierbovenop doet is de digest uit `target/jib-image.digest` naar
`$GITHUB_OUTPUT` schrijven voor de deploy-op-digest.
