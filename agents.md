# Agent Notes

This doc is for future automation agents working on the SmartEnv plugin. Keep edits concise and append new sections instead of rewriting history.

## Codebase Pointers

- Plugin XML: `src/main/resources/META-INF/plugin.xml`
- Gradle build: `build.gradle.kts`
- Core parsing: `src/main/kotlin/dev/smartenv/engine/SmartEnvFileProcessor.kt`
- UI configuration: `src/main/kotlin/dev/smartenv/ui/SmartEnvSettingsConfigurable.kt`
- Samples and docs: `docs/` and `docs/samples/`

## Build & Test

- Build plugin: `./gradlew clean buildPlugin` (ZIP in `build/distributions`)
- Run IDE sandbox: `./gradlew runIde`

## Release Checklist

- Update version and change notes in both `build.gradle.kts` and `plugin.xml`
- Verify icon in `src/main/resources/META-INF/pluginIcon.svg`
- Validate since/until build in Gradle patchPluginXml task
- Ensure README marketplace copy is current

## Dev Conventions

- Kotlin 17 toolchain; IntelliJ platform 2025.1 (IC) with Java plugin
- Prefer `rg` for searches; avoid destructive git operations
- Default unknown file extensions to JSON blob mode (see SmartEnvFileProcessor)

## Logging/Debug

- IDEA logs: `idea.log` (from sandbox or main IDE)
- SmartEnv debug logs use IntelliJ Logger; enable debug to trace parsing

## Release 1.1.0 Notes

- Version bumped to `1.1.0` in `build.gradle.kts` and `plugin.xml`, with matching change notes.
- README updated to describe the new inheritance flow, preview UI, and unified entries table before publishing.

## Release 1.1.1 Notes

- Version bumped to `1.1.1` in `build.gradle.kts` and `plugin.xml`.
- Change notes now highlight the compatibility extension through build `252.28238.7`.

## Release 1.1.2 Notes

- Version bumped to `1.1.2` in `build.gradle.kts` and `plugin.xml`, with change notes covering the parsing/UI upgrades.
- Dotenv/properties parsing now handles `export`, inline comments, and quoted values; missing files emit clearer notes that surface in the UI.
- Settings adds a Status column fed by resolver diagnostics, folder imports skip `.git/.idea/build/out`, and large imports prompt at 100 files.
- Preview table emphasizes override-heavy keys, color badges gained contrast borders, and Quick Settings now offers an "Open SmartEnv Preview." action.

## Release 1.1.3 Notes

- Version bumped to `1.1.3` in `build.gradle.kts` and `plugin.xml`, and README updated with the new release summary.
- IntelliJ compatibility extended through build `253.28294.334` (patchPluginXml `untilBuild` + change notes refreshed).
