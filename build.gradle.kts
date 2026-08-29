import org.springframework.boot.gradle.plugin.SpringBootPlugin
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
}

repositories {
    mavenCentral()
}

dependencies {
    // The Boot BOM imports kotlin-bom at Boot's own Kotlin version, which would quietly pin the
    // version-less stdlib/reflect/test artifacts below the 2.4.10 compiler this build uses.
    // Declaring the Kotlin BOM first keeps the compiler and the runtime libraries aligned.
    implementation(platform(kotlin("bom")))
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))

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

// The ONNX models are not committed. Each artefact is fetched once from a pinned Hugging Face
// revision and verified against the committed checksums, so a fresh clone builds without manual
// steps and a corrupted or swapped download fails the build instead of silently changing every
// vector. Local names are namespaced because both checkpoints ship a model.onnx and a
// tokenizer.json, and checksums.sha256 is keyed by file name. The sparse weights come from a
// third-party ONNX export whose safetensors, tokenizer and IDF table are byte-identical to the
// official repository's; the checksum pins what was measured, the revision only makes the download
// reproducible.
object HuggingFace {
    fun url(
        repo: String,
        revision: String,
        path: String,
    ): String = "https://huggingface.co/$repo/resolve/$revision/$path"
}

val miniLm = "sentence-transformers/all-MiniLM-L6-v2"
val miniLmRevision = "1110a243fdf4706b3f48f1d95db1a4f5529b4d41"
val sparseExport = "seerware/opensearch-neural-sparse-encoding-doc-v2-mini"
val sparseExportRevision = "925b75d04db3ac69dc05881534584078792eb6ea"
val sparseModel = "opensearch-project/opensearch-neural-sparse-encoding-doc-v2-mini"
val sparseModelRevision = "4af867a426867dfdd744097531046f4289a32fdd"
val modelSources =
    mapOf(
        "model.onnx" to HuggingFace.url(miniLm, miniLmRevision, "onnx/model.onnx"),
        "tokenizer.json" to HuggingFace.url(miniLm, miniLmRevision, "tokenizer.json"),
        "sparse-model.onnx" to HuggingFace.url(sparseExport, sparseExportRevision, "onnx/fp32/model.onnx"),
        "sparse-tokenizer.json" to HuggingFace.url(sparseModel, sparseModelRevision, "tokenizer.json"),
        "sparse-idf.json" to HuggingFace.url(sparseModel, sparseModelRevision, "idf.json"),
    )

// An object rather than a script-level function: the task action below must not capture the build
// script instance, or the configuration cache cannot serialize it.
object Digest {
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
}

val provisionModel =
    tasks.register("provisionModel") {
        group = "build setup"
        description = "Downloads the pinned ONNX models, tokenizers and IDF table into models/."

        val modelDir = layout.projectDirectory.dir("models")
        val checksumFile = modelDir.file("checksums.sha256").asFile
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

            sources.forEach { (fileName, url) ->
                val target = modelDir.file(fileName).asFile
                val want = requireNotNull(expected[fileName]) { "No checksum recorded for $fileName" }
                if (target.exists() && Digest.sha256(target) == want) {
                    logger.lifecycle("models/$fileName is present and matches its checksum")
                    return@forEach
                }
                logger.lifecycle("Downloading models/$fileName from $url")
                target.parentFile.mkdirs()
                URI(url).toURL().openStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                val got = Digest.sha256(target)
                check(got == want) {
                    "Checksum mismatch for models/$fileName: expected $want but got $got. " +
                        "Delete the file and retry; if it persists the upstream revision has changed."
                }
            }
        }
    }

// JEP 472: JNI calls from the ONNX Runtime and tokenizer libraries warn on JDK 24+ unless the
// module is granted native access explicitly.
val nativeAccess = listOf("--enable-native-access=ALL-UNNAMED")

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    dependsOn(provisionModel)
    jvmArgs(nativeAccess)
    // Forwards -Dcandidates=<dir> and -Dsparse-candidates=<dir> to the test JVM for the (normally
    // skipped) model comparison experiments; see ModelSelectionExperiment and SparseModelExperiment.
    systemProperty("candidates", providers.systemProperty("candidates").getOrElse(""))
    systemProperty("sparse-candidates", providers.systemProperty("sparse-candidates").getOrElse(""))
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    dependsOn(provisionModel)
    jvmArgs(nativeAccess)
}

springBoot {
    // Populates /actuator/info, which is exposed — a deployed instance can say what it is.
    buildInfo()
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    manifest {
        // JDK 24+ honours this for `java -jar`, so running the jar directly needs no flags.
        // The container ENTRYPOINT still passes the flag explicitly: the extracted-layout
        // JarLauncher is not started with -jar and does not read this attribute.
        attributes("Enable-Native-Access" to "ALL-UNNAMED")
    }
}
