plugins {
    kotlin("jvm") version "2.1.0"
    id("org.jetbrains.intellij") version "1.17.0"
}


group = "dev.smartenv"
version = "1.1.1"

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
            <h3>1.1.1 - Compatibility refresh</h3>
            <ul>
                <li>Extend IntelliJ build compatibility through 252.28238.7 while keeping the existing feature set intact.</li>
            </ul>
        """.trimIndent())
        sinceBuild.set("251.23774.435")
        untilBuild.set("252.28238.7")
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
