# =============================================================================
#  oculix-runner : JVM OculiX chaude derriere une API HTTP, base SQLite
# -----------------------------------------------------------------------------
#  Trois etapes :
#    1. oculix  : le jar OculiX, soit depose dans jars/oculix.jar, soit telecharge
#                 depuis la release GitHub (SHA-256 verifie)
#    2. build   : compilation du service (Maven) contre ce jar
#    3. runtime : JRE 17 + Xvfb + les deux jars + le service au demarrage
#
#  Le jar OculiX est la brique de tout : le service ne fait que le charger une
#  fois et lui passer des scripts. Rien n'est modifie dans OculiX.
#
#  Xvfb : l'initialisation AWT d'OculiX exige un affichage, meme quand le
#  script ne pilote qu'un ecran VNC distant (Sikuli.py initialise l'ecran
#  primaire a l'import). Un seul Xvfb pour toute la vie du service.
# =============================================================================

# -----------------------------------------------------------------------------
# 1. Le jar OculiX
#    Pour utiliser un autre jar (build local, release candidate) : le copier
#    dans jars/oculix.jar avant le build, il prend le pas sur le telechargement.
# -----------------------------------------------------------------------------
FROM alpine:3.20 AS oculix
ARG OCULIX_VERSION=4.0.0
ARG OCULIX_SHA256=95bd353ddd92af9779845c83c36646bc5e5ca5e767196818615444c02d49533d
RUN apk add --no-cache curl
COPY jars/ /jars/
RUN if [ -f /jars/oculix.jar ]; then \
      echo "OculiX: jar local jars/oculix.jar"; \
    else \
      echo "OculiX: telechargement de la release ${OCULIX_VERSION}"; \
      curl -fL --retry 3 \
        "https://github.com/oculix-org/Oculix/releases/download/v${OCULIX_VERSION}/oculixide-${OCULIX_VERSION}-linux.jar" \
        -o /jars/oculix.jar \
      && printf '%s  %s\n' "$OCULIX_SHA256" /jars/oculix.jar | sha256sum -c -; \
    fi

# -----------------------------------------------------------------------------
# 2. Le service
# -----------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY --from=oculix /jars/oculix.jar /opt/oculix/oculix.jar
COPY service/pom.xml ./
RUN mvn -q -B -Doculix.jar=/opt/oculix/oculix.jar dependency:go-offline
COPY service/src ./src
RUN mvn -q -B -Doculix.jar=/opt/oculix/oculix.jar package

# -----------------------------------------------------------------------------
# 3. L'image finale
# -----------------------------------------------------------------------------
FROM eclipse-temurin:17-jre-jammy

ENV DEBIAN_FRONTEND=noninteractive \
    LANG=C.UTF-8 \
    OCULIX_HOME=/opt/oculix \
    OCULIX_JAR=/opt/oculix/oculix.jar \
    RUNNER_HTTP_PORT=8765 \
    RUNNER_DATA_DIR=/workdir \
    RUNNER_DEBUG_LEVEL=3

# Xvfb et les libs X que les natives d'OculiX (OpenCV, Tesseract, AWT) attendent
RUN apt-get update && apt-get install -y --no-install-recommends \
        curl \
        ca-certificates \
        libgl1 \
        libglib2.0-0 \
        libsm6 \
        libxext6 \
        libxrender1 \
        libfreetype6 \
        fontconfig \
        xvfb \
        xauth \
        libxtst6 \
        libxi6 \
    && rm -rf /var/lib/apt/lists/*

COPY --from=oculix /jars/oculix.jar /opt/oculix/oculix.jar
COPY --from=build /build/target/oculix-runner-service.jar /opt/oculix/oculix-runner-service.jar
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh && mkdir -p /workdir

# Base SQLite, scripts et artefacts des runs : a monter sur un volume
VOLUME /workdir
EXPOSE 8765

HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=3 \
    CMD curl -fs "http://localhost:${RUNNER_HTTP_PORT}/health" | grep -q '"engine":"ready"' || exit 1

ENTRYPOINT ["/entrypoint.sh"]
