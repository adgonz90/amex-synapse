# The ${serviceName} service's container image without buildpacks, from the executable jar that mvn package builds
# (with clean, so that no code from an earlier -Paot build is left in it), from the directory of the aggregator pom:
#
#   mvn clean package -DskipTests
#   docker build -t ${serviceName}:dockerfile service-${serviceName}
#
# mvn -Pimage package (synapse-parent's Paketo settings) is the other way to build it; both start the JVM with an AOT
# cache (JEP 483) recorded by a training run in the build environment. Synapse's parent POM documentation
# (https://github.com/americanexpress/synapse/blob/main/docs/modules/ROOT/pages/parent.adoc, Images) says how they
# differ and how to size the heap for small pods (JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=...).
#
# The JRE is Eclipse Temurin 25.0.5+8 on Ubuntu 26.04 (resolute), pinned by digest so every build gets the same bytes;
# the tag says which version the digest is. Its release notes
# (https://api.adoptium.net/v3/info/release_notes/jdk-25.0.5+8) include JDK-8388703, a fix for a JVM that started
# with an AOT cache crashing depending on the CPU's instructions. To update it, read the next release's notes, then
# the new digest with docker buildx imagetools inspect eclipse-temurin:<version>-jre-resolute.

# Extracts the jar in a stage of its own: the runtime stage gets only the extracted layers.
FROM eclipse-temurin:25.0.5_8-jre-resolute@sha256:b6e00330337143a2c1a20f1c1c693d4b6ce9a3c30ade8b9d03fc68cc9e3871e7 AS builder
WORKDIR /builder
# The jar that package built; .dockerignore keeps everything else out of the build context.
ARG JAR_FILE=target/*.jar
COPY ${JAR_FILE} application.jar
# The layout Boot's jarmode writes: the dependencies, the loader, the snapshot dependencies and the application, one
# directory each, so a change to the code rebuilds only the last layer. An AOT cache works only with this extracted
# form, never with the executable jar itself.
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM eclipse-temurin:25.0.5_8-jre-resolute@sha256:b6e00330337143a2c1a20f1c1c693d4b6ce9a3c30ade8b9d03fc68cc9e3871e7
WORKDIR /application
COPY --from=builder /builder/extracted/dependencies/ ./
COPY --from=builder /builder/extracted/spring-boot-loader/ ./
COPY --from=builder /builder/extracted/snapshot-dependencies/ ./
COPY --from=builder /builder/extracted/application/ ./
# The training run (JEP 514): the JVM runs the application, records the classes it loads and links, and writes them to
# app.aot. It starts in the build environment (application-build.yaml, data-${serviceName}'s config-build.yaml), which a
# system property sets, so it is the one active profile, and exits once the context has refreshed: every bean is
# created, and nothing connects to the database, which isn't there. A run of the image has no trace of this profile;
# ENV picks its environment. A run that fails to start fails the build, and so does a run that exits 0 without writing
# the cache (test -s): the image would start without it and only warn. The collector is G1, here and in the
# ENTRYPOINT, so every run uses the collector the cache was trained with: left to the JVM, the choice depends on the
# machine: in a 512 MB container the JVM picks the Serial collector, and its small default initial heap there can't
# hold the cache's archived objects, so the JVM stops at startup (for the reference bookstore's cache, 6074 KB against
# about 5504 KB of old generation at an 8 MB heap; parent.adoc, The garbage collector). To use another collector,
# change both lines.
RUN java -XX:+UseG1GC -XX:AOTCacheOutput=app.aot -Dspring.context.exit=onRefresh -Dspring.profiles.active=build -jar application.jar \
    && test -s app.aot
# Runs as the image's unprivileged user (ubuntu, uid 1000), not as root.
USER 1000:1000
# REST and gRPC.
EXPOSE 8080 9090
# No HEALTHCHECK: the orchestrator probes /health (the readiness group, on the REST port) itself, and the JRE image
# has no curl or wget; don't install one just for Docker's own check.
# The same java, collector, class path and jar as the training run, as the AOT cache requires: when the JVM can't use
# the cache, it warns and starts without it. JVM options for a deployment, such as the heap size, go in
# JAVA_TOOL_OPTIONS (not another collector: the command line's -XX:+UseG1GC would conflict with it).
ENTRYPOINT ["java", "-XX:+UseG1GC", "-XX:AOTCache=app.aot", "-jar", "application.jar"]
