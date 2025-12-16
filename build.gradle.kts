plugins {
    kotlin("jvm") version "2.1.0"
    id("org.jetbrains.intellij") version "1.17.0"
}


group = "dev.smartenv"
version = "1.1.3"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.0")
    implementation("org.yaml:snakeyaml:2.0")
}

intellij {
    version.set("2025.1")
    type.set("IC")
    plugins.set(listOf("java"))
}

kotlin {
    jvmToolchain {
        (this as JavaToolchainSpec).languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks {
    patchPluginXml {
        changeNotes.set("""
            <h3>1.1.3 - 253 build compatibility</h3>
            <ul>
                <li>Extend IntelliJ build compatibility through 253.28294.334 so SmartEnv stays usable on the latest 2025.2 builds.</li>
            </ul>
            <h3>1.1.2 - Preview & diagnostics polish</h3>
            <ul>
              <li>Harden dotenv/properties parsing (export lines, inline comments, quoted values) and surface clearer missing-file diagnostics.</li>
              <li>Add Status feedback inside SmartEnv settings, including missing/failed files and zero-key parses, plus safer folder imports with a 100-file confirmation.</li>
              <li>Emphasize override-heavy keys in the Preview table and add a Quick Settings shortcut that opens the Settings + Preview pane directly.</li>
            </ul>
            <h3>1.1.1 - Compatibility refresh</h3>
            <ul>
                <li>Extend IntelliJ build compatibility through 252.28238.7 while keeping the existing feature set intact.</li>
            </ul>
        """.trimIndent())
        sinceBuild.set("251.23774.435")
        untilBuild.set("253.28294.334")
    }

    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += "-Xjvm-default=all"
    }
    }

    // buildSearchableOptions tries to start a headless IDE session and currently crashes on IJ 2025.1,
    // so disable it until the upstream tooling supports the new platform.
    buildSearchableOptions {
        enabled = false
    }
}
