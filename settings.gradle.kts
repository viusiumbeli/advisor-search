plugins {
    // Auto-provisions the JDK the toolchain asks for, so a reviewer without a local JDK 25
    // still gets a working `./gradlew build` instead of "No matching toolchains found".
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "advisor-search"
