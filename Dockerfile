# Multi-stage build voor de NMC Quarkus-applicatie (fast-jar).
# Stage 1 bouwt de fast-jar met de Maven wrapper; stage 2 draait die op een JRE.

# --- Build stage ---
FROM eclipse-temurin:25-jdk AS build
WORKDIR /build

# Eerst wrapper + pom kopiëren zodat de dependency-download-laag gecachet wordt
# zolang pom.xml niet verandert.
COPY mvnw ./
COPY .mvn .mvn
COPY pom.xml ./
RUN ./mvnw -B -q dependency:go-offline

# Daarna de broncode en bouwen (codegen van OpenAPI-clients gebeurt in package).
COPY src src
RUN ./mvnw -B -DskipTests package

# --- Runtime stage ---
FROM eclipse-temurin:25-jre AS runtime
WORKDIR /work

# Quarkus fast-jar layout: deze vier onderdelen in deze volgorde voor optimale
# Docker-laagcaching (lib verandert het minst vaak, app/ het vaakst).
COPY --from=build /build/target/quarkus-app/lib/ ./lib/
COPY --from=build /build/target/quarkus-app/*.jar ./
COPY --from=build /build/target/quarkus-app/app/ ./app/
COPY --from=build /build/target/quarkus-app/quarkus/ ./quarkus/

# Niet als root draaien.
RUN useradd -r -u 1001 nmc
USER 1001

EXPOSE 8080
ENV QUARKUS_HTTP_HOST=0.0.0.0
ENTRYPOINT ["java", "-jar", "quarkus-run.jar"]
