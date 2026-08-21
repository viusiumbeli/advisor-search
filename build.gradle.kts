import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI
import java.security.MessageDigest

plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.spring") version "2.4.10"
    id("org.springframework.boot") version "4.1.1"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
}

group = "com.advisorsearch"
version = "0.1.0"

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.0")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // Spring Boot 4 keeps each technology's auto-configuration in its own artifact; flyway-core
    // alone no longer brings the migration bootstrap with it.
    implementation("org.springframework.boot:spring-boot-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // Embeddings run in-process: ONNX Runtime and the HuggingFace tokenizer both ship their
    // native libraries inside the jar, so the container needs no network at run time.
    implementation("com.microsoft.onnxruntime:onnxruntime:1.29.0")
    implementation("ai.djl.huggingface:tokenizers:0.36.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Spring Boot 4 split the test auto-configurations into per-technology artifacts; MockMvc
    // support is no longer part of the umbrella starter.
    testImplementation("org.springframework.boot:spring-boot-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
}

// JEP 472: JNI calls from the ONNX Runtime and tokenizer libraries warn on JDK 24+ unless the
// module is granted native access explicitly.
val nativeAccess = listOf("--enable-native-access=ALL-UNNAMED")

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    dependsOn(provisionModel)
    jvmArgs(nativeAccess)
    // Forwards -Dcandidates=<dir> to the test JVM for the (normally skipped) model comparison
    // experiment; see ModelSelectionExperiment.
    systemProperty("candidates", providers.systemProperty("candidates").getOrElse(""))
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    dependsOn(provisionModel)
    jvmArgs(nativeAccess)
}

// The 90 MB ONNX model is not committed. It is fetched once from a pinned Hugging Face revision
// and verified against the committed checksums, so a fresh clone builds without manual steps and
// a corrupted or swapped download fails the build instead of silently changing every embedding.
val modelRevision = "1110a243fdf4706b3f48f1d95db1a4f5529b4d41"
val modelSources =
    mapOf(
        "model.onnx" to "onnx/model.onnx",
        "tokenizer.json" to "tokenizer.json",
    )

val provisionModel =
    tasks.register("provisionModel") {
        group = "build setup"
        description = "Downloads the pinned all-MiniLM-L6-v2 ONNX model and tokenizer into models/."

        val modelDir = layout.projectDirectory.dir("models")
        val checksumFile = modelDir.file("checksums.sha256").asFile
        val revision = modelRevision
        val sources = modelSources
        inputs.file(checksumFile)
        outputs.files(sources.keys.map { modelDir.file(it) })

        doLast {
            val expected =
                checksumFile
                    .readLines()
                    .filter { it.isNotBlank() }
                    .associate { line ->
                        val (sha, name) = line.trim().split(Regex("\\s+"), limit = 2)
                        name.trim() to sha
                    }

            sources.forEach { (fileName, remotePath) ->
                val target = modelDir.file(fileName).asFile
                val want = requireNotNull(expected[fileName]) { "No checksum recorded for $fileName" }
                if (target.exists() && sha256(target) == want) {
                    logger.lifecycle("models/$fileName is present and matches its checksum")
                    return@forEach
                }
                val url = "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/$revision/$remotePath"
                logger.lifecycle("Downloading models/$fileName from $url")
                target.parentFile.mkdirs()
                URI(url).toURL().openStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                val got = sha256(target)
                check(got == want) {
                    "Checksum mismatch for models/$fileName: expected $want but got $got. " +
                        "Delete the file and retry; if it persists the upstream revision has changed."
                }
            }
        }
    }

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
