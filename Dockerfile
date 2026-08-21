# The build stage downloads the embedding model once and the runtime stage carries it in the image.
# Nothing is fetched at container start: ONNX Runtime and the tokenizer both ship their native
# libraries inside their jars, so a running container needs no network beyond Postgres.
#
# The build stage is pinned to the builder's own architecture. Its outputs — a jar and the model
# files — are architecture independent, so there is nothing to gain from emulating a foreign
# architecture through QEMU to produce them, and a multi-architecture build would otherwise spend
# minutes doing exactly that.
FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# Dependencies and the model resolve from the build files alone, so editing source does not
# re-download 90 MB of model or the Gradle distribution.
COPY gradle gradle
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY models/checksums.sha256 models/checksums.sha256
RUN ./gradlew --no-daemon provisionModel

COPY src src
RUN ./gradlew --no-daemon bootJar -x test

# Splitting the fat jar into Boot's layers keeps the ~110 MB of dependencies in their own image
# layer: a source change re-pushes only the ~1 MB application layer, not the whole jar.
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

# Self-describing image: `docker run` gets the same health semantics compose declares.
# (No --start-interval here: it needs Docker Engine 25+ and is a hard parse error on 24, unlike
# the compose field of the same name, which older engines simply ignore.)
HEALTHCHECK --interval=5s --timeout=5s --retries=12 --start-period=30s \
    CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1

# Flags live in the ENTRYPOINT rather than JAVA_TOOL_OPTIONS: the env var is inherited by every JVM
# in the container and echoes itself to stderr on each start. MaxRAMPercentage leaves headroom for
# ONNX Runtime's arenas (native memory outside the Java heap); the native-access flag is JEP 472 —
# the extracted-layout launcher is not started with -jar, so the jar's manifest attribute
# equivalent does not apply here.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=65", "--enable-native-access=ALL-UNNAMED", \
            "org.springframework.boot.loader.launch.JarLauncher"]
