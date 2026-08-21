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

FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

# curl is here for the compose healthcheck, which has to run inside the container.
RUN apt-get update \
    && apt-get install --yes --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --create-home --uid 10001 advisor

COPY --from=build /workspace/build/libs/advisor-search-*.jar app.jar
COPY --from=build /workspace/models models

USER advisor
EXPOSE 8080

# MaxRAMPercentage leaves headroom for the ONNX Runtime arenas, which are native memory and sit
# outside the Java heap. --enable-native-access silences the JEP 472 warning for the JNI calls.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65 --enable-native-access=ALL-UNNAMED"

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
