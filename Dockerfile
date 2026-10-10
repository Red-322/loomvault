# syntax=docker/dockerfile:1
# =============================================================================================
#  LoomVault — multi-stage container build
#
#  Stage 1 (build)   : Maven + JDK 21 builds the executable fat jar.
#  Stage 2 (runtime) : a slim JRE image runs it as an unprivileged user.
#
#  Build :  docker build -t loomvault .
#  Run   :  docker run -p 8080:8080 loomvault
#  The default profile expects PostgreSQL + Redis (see deploy/docker-compose.yml). For a
#  zero-dependency local run append:  --spring.profiles.active=standalone
# =============================================================================================

# Base images are declared as ARGs so CI can pin them (e.g. by digest) without editing this file.
ARG JDK_BUILD_IMAGE=maven:3.9-eclipse-temurin-21
ARG JRE_RUNTIME_IMAGE=eclipse-temurin:21-jre-alpine

# ---------------------------------------------------------------------------------------------
# Stage 1 — build
# ---------------------------------------------------------------------------------------------
FROM ${JDK_BUILD_IMAGE} AS build

WORKDIR /workspace

# Copy every POM first. This layer only changes when a POM changes, so the slow dependency
# download below is reused across source-only edits. A multi-module reactor needs all module
# POMs present before Maven can even parse the build.
COPY pom.xml ./
COPY loomvault-core/pom.xml   loomvault-core/pom.xml
COPY loomvault-llm/pom.xml    loomvault-llm/pom.xml
COPY loomvault-store/pom.xml  loomvault-store/pom.xml
COPY loomvault-server/pom.xml loomvault-server/pom.xml

# Warm the Maven repository and validate the reactor graph. At this point the modules still have
# no sources, so `repackage` is skipped — this step exists purely to populate the dependency
# cache layer. It plays the role of `dependency:go-offline`, but it also proves that the reactor
# resolves: `go-offline` cannot resolve inter-module references that have not been packaged yet,
# whereas this run builds (empty) module artifacts inside the same reactor session.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -DskipTests -Dspring-boot.repackage.skip=true package

# Now bring in the sources and build the real, executable jar.
# <finalName> is `loomvault` (loomvault-server/pom.xml) -> loomvault-server/target/loomvault.jar
COPY loomvault-core/src   loomvault-core/src
COPY loomvault-llm/src    loomvault-llm/src
COPY loomvault-store/src  loomvault-store/src
COPY loomvault-server/src loomvault-server/src

RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -DskipTests package

# ---------------------------------------------------------------------------------------------
# Stage 2 — runtime
# ---------------------------------------------------------------------------------------------
FROM ${JRE_RUNTIME_IMAGE} AS runtime

# OCI image-spec annotations (title / source / license).
LABEL org.opencontainers.image.title="LoomVault" \
      org.opencontainers.image.description="Durable, resumable, budget-aware execution engine for AI agent workflows." \
      org.opencontainers.image.source="https://github.com/loomvault/loomvault" \
      org.opencontainers.image.url="https://github.com/loomvault/loomvault" \
      org.opencontainers.image.licenses="Apache-2.0"

# Dedicated unprivileged account (uid/gid 10001) — never run the JVM as root.
RUN addgroup -g 10001 -S loomvault \
 && adduser  -u 10001 -S -G loomvault -h /app -s /sbin/nologin loomvault

WORKDIR /app

# Copy only the fat jar built in stage 1; everything else stays out of the final image.
COPY --from=build --chown=loomvault:loomvault \
     /workspace/loomvault-server/target/loomvault.jar /app/app.jar

USER loomvault

EXPOSE 8080

# Extra JVM flags can be injected at runtime, e.g.
#   docker run -e JAVA_OPTS="-Xmx512m" -p 8080:8080 loomvault
ENV JAVA_OPTS=""

# The alpine base ships busybox, which provides `wget`; `curl` is not guaranteed to be present.
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD wget -q -O- http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
