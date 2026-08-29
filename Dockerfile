# The models are baked in at build time; nothing is fetched at container start, because ONNX Runtime
# and the tokenizer ship their native libraries inside their jars.
#
# The build stage is pinned to the builder's own architecture: its outputs are architecture
# independent, so emulating a foreign one through QEMU to produce them would cost minutes and buy
# nothing.
FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# Dependencies and the models resolve from the build files alone, so editing source does not
# re-download ~230 MB of models or the Gradle distribution.
COPY gradle gradle
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY models/checksums.sha256 models/checksums.sha256
RUN ./gradlew --no-daemon provisionModel

COPY src src
RUN ./gradlew --no-daemon bootJar -x test

# Boot's layers keep the ~110 MB of dependencies separate, so a source change re-pushes ~1 MB.
RUN java -Djarmode=tools -jar build/libs/advisor-search-*.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

# curl exists for the container healthcheck, which has to run inside the container.
RUN apt-get update \
    && apt-get install --yes --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --create-home --uid 10001 advisor

# Most stable layer first; the application layer last so it is the only one that changes often.
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
COPY --from=build /workspace/models models

USER advisor
EXPOSE 8080

# So `docker run` gets the same health semantics compose declares. No --start-interval: it needs
# Docker Engine 25+ and is a hard parse error on 24, unlike the compose field of the same name.
HEALTHCHECK --interval=5s --timeout=5s --retries=12 --start-period=30s \
    CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1

# Flags here rather than JAVA_TOOL_OPTIONS, which every JVM in the container would inherit and echo
# to stderr. MaxRAMPercentage leaves headroom for ONNX Runtime's arenas (native memory, outside the
# heap); the native-access flag is JEP 472, needed because the extracted-layout launcher is not
# started with -jar and so cannot use the manifest attribute.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=65", "--enable-native-access=ALL-UNNAMED", \
            "org.springframework.boot.loader.launch.JarLauncher"]
