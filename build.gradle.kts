import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.13.1"
}

group = "dev.smartenv"
version = "1.1.8"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.0")
    implementation("org.yaml:snakeyaml:2.0")

    intellijPlatform {
        create(IntelliJPlatformType.IntellijIdeaCommunity, "2025.1")
        bundledPlugin("com.intellij.java")
        pluginVerifier()
    }
}

kotlin {
    jvmToolchain {
        (this as JavaToolchainSpec).languageVersion.set(JavaLanguageVersion.of(21))
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "251.23774.435"
        }

        changeNotes = """
            <h3>1.1.8 - IntelliJ 2026.1 readiness</h3>
            <ul>
                <li>Migrate build tooling from Gradle IntelliJ Plugin 1.x to IntelliJ Platform Gradle Plugin 2.x.</li>
                <li>Move Java/Kotlin build baseline to 21 to align with IntelliJ 2025.1+ platform requirements.</li>
                <li>Add plugin verification task coverage for 2025.1, 2025.3, and 2026.1 target IDEs.</li>
            </ul>
            <h3>1.1.7 - Open-ended compatibility window</h3>
            <ul>
                <li>Remove the explicit <code>untilBuild</code> cap so SmartEnv does not require release bumps for each new IntelliJ patch build.</li>
            </ul>
            <h3>1.1.6 - 253 build compatibility</h3>
            <ul>
                <li>Extend IntelliJ build compatibility through 253.31033.145 so SmartEnv stays usable on the latest 2025.3 builds.</li>
            </ul>
            <h3>1.1.5 - 253 build compatibility</h3>
            <ul>
                <li>Extend IntelliJ build compatibility through 253.30387.90 so SmartEnv stays usable on the latest 2025.3 builds.</li>
            </ul>
            <h3>1.1.4 - 253 build compatibility</h3>
            <ul>
                <li>Extend IntelliJ build compatibility through 253.29346.240 so SmartEnv stays usable on the latest 2025.3 builds.</li>
            </ul>
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
        """.trimIndent()
    }

    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2025.1")
            create(IntelliJPlatformType.IntellijIdeaUltimate, "2025.3")
            create(IntelliJPlatformType.IntellijIdeaUltimate, "2026.1")
        }
    }
}

tasks {
    withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
            freeCompilerArgs.add("-Xjvm-default=all")
        }
    }

    // buildSearchableOptions tries to start a headless IDE session and currently crashes on IJ 2025.1,
    // so disable it until the upstream tooling supports the new platform.
    buildSearchableOptions {
        enabled = false
    }

    register("verifyAgainstTargetIdes") {
        group = "verification"
        description = "Runs JetBrains Plugin Verifier against 2025.1, 2025.3, and 2026.1."
        dependsOn(named("verifyPlugin"))
    }

    withType<VerifyPluginTask>().configureEach {
        failureLevel = listOf(
            VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            VerifyPluginTask.FailureLevel.INVALID_PLUGIN
        )
    }
}
