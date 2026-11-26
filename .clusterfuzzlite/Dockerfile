# De NMC compileert met --release 25, dus we hebben JDK 25 nodig
# (base-builder-jvm draait op Ubuntu 20.04 en levert JDK 17 mee).
# Ubuntu 20.04 heeft geen openjdk-25 pakket, daarom nemen we Temurin 25 over
# uit het officiële image. Het platform staat vast op amd64, net als de
# gepinde base-builder-jvm.
FROM --platform=linux/amd64 eclipse-temurin:25.0.5.1_2-jdk@sha256:ce2ef9d2d67a68de5e196d4de9d743327c44a10b57fe02ec39f572c652cacfd3 AS jdk

FROM gcr.io/oss-fuzz-base/base-builder-jvm:v1@sha256:f34c2b102970b3c048d9ce77ee21cc16c627747e2c3b05f2dfbf3da221047496

COPY --from=jdk /opt/java/openjdk /usr/lib/jvm/java-25-temurin

ENV JAVA_HOME=/usr/lib/jvm/java-25-temurin
ENV PATH=$JAVA_HOME/bin:$PATH

COPY . $SRC/moza-notificatiemanagementcomponent
WORKDIR $SRC/moza-notificatiemanagementcomponent
RUN chmod +x mvnw
COPY .clusterfuzzlite/build.sh $SRC/
