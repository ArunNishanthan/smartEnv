# SmartEnv IntelliJ Plugin

SmartEnv lets you compose runtime environment variables from multiple files and profile them per run configuration. This README is structured to double as the Marketplace description and a quick start guide.

## What It Does

- Merge values from `.env`, `.properties`, `.yaml`, `json` (flattened or blob), and plain `key=value` files.
- Profile-based loading: choose which files apply to a run configuration.
- Quick Settings popup to switch profiles without reopening the settings dialog.
- Safe preview: see what will be injected before the run starts.

## Features

- Auto-detects file type by extension; unknown extensions default to JSON blob mode so content is still injectable.
- Two JSON modes: flatten to dot-notation keys, or keep as a single blob under a custom key.
- Profile inheritance (extend other profiles) with full chain merging of file layers and custom overrides.
- Unified entries table (files + custom values) with multi-select, drag ordering (via move buttons), Delete, and a dropdown Add button.
- Status bar widget + Quick Settings + toolbar toggle to flip profiles and preview without opening Settings.

## What's New in 1.1.3

- Extend IntelliJ build compatibility through 253.28294.334 so SmartEnv keeps running on the latest 2025.2 platform builds.
- Refresh Marketplace metadata/change notes in preparation for the 1.1.3 publication.

## What's New in 1.1.0

- Profile inheritance now merges files and custom overrides with predictable order.
- Preview panel rebuilt with search, filters, smooth scrolling, and detailed override stacks.
- File/custom entries table unified with multi-select deletion, move up/down, and a dropdown Add button for files or custom values.

## Quick Start

1. Install the plugin from the Marketplace (search for "SmartEnv") or load the built ZIP (see Build & Publish).
2. Open `Settings | Tools | SmartEnv` and enable the plugin.
3. Create a profile, set its color/icon, and add files:
   - Pick `Format` = `Auto-detect` or explicitly choose `JSON (blob key)`, `JSON (flatten)`, `YAML`, `Properties`, `Dotenv`, or `Plain text`.
   - For JSON blob mode, set `Key` if you want a custom top-level key; otherwise the filename is used.
4. Assign the profile to a run configuration and run/debug.
5. Use the SmartEnv status widget to switch profiles on the fly.

## Example Setup

```
project/
├─ docs/samples/test-values.env
├─ docs/samples/test-values.json
├─ docs/samples/test-values-blob.common.config   # unknown extension → JSON blob mode
└─ docs/samples/test-values.yaml
```

- Add each file to a profile with `Format = Auto-detect` (the `.common.config` file will fall back to JSON blob mode).
- For the blob file, set `Key = twSettings` to store it under `twSettings`.
- Preview shows merged output; e.g. `twSettings` contains the raw JSON string, while other files are flattened.

## File Handling Notes

- If SmartEnv cannot infer a type from the filename, it defaults to JSON blob mode to ensure the content is injectable.
- Sample files live in `docs/samples/` (env, properties, yaml, json, and json-blob examples).

## Build & Publish

- Build the plugin ZIP: `./gradlew buildPlugin` (artifact lands in `build/distributions`).
- Verify locally: `./gradlew runIde`.
- Publish to JetBrains Marketplace: upload the ZIP in the Marketplace publisher portal and reuse this README for the listing body.

## Support

- File a ticket in the repository issues or contact the maintainers with logs from `idea.log` plus the SmartEnv preview output.

## Getting Started

<ul>
  <li><strong>Install the plugin:</strong> open <em>Settings | Plugins</em>, search for “SmartEnv”, and click <em>Install</em>. Restart the IDE so the Quick Settings action, toolbar button, and run-configuration hook all load correctly.</li>
  <li><strong>Create your first profile:</strong> head to <em>Settings | Tools | SmartEnv</em>, enable the plugin, and add a profile. Use <em>Add file or folder</em> to attach `.env`, `.properties`, YAML, JSON (flattened/blob), or plain `key=value` text files. Auto-detect covers most cases; select JSON blob mode when you need an entire JSON payload under one key.</li>
  <li><strong>Assign the profile to runs:</strong> open a Run/Debug configuration, switch to the SmartEnv tab, and choose the profile you just created. SmartEnv injects merged variables just before the process launches.</li>
  <li><strong>Preview and iterate:</strong> click the preview toggle inside SmartEnv settings or use <em>Quick Settings → Open SmartEnv Preview.</em> to inspect overrides, resolve conflicts, and catch missing/failed files via the Status column before you run.</li>
  <li><strong>Complementary/Conflicting plugins:</strong> SmartEnv works alongside JetBrains’ native Environment Variables UI and Docker/Kubernetes integrations. Avoid enabling other environment-injection plugins on the same run configuration—they can override SmartEnv or race during launch. If you must keep them installed, disable their per-configuration hooks so SmartEnv stays authoritative.</li>
</ul>

## Technical Information

- SmartEnv registers its actions, run-configuration extension, tool window, and configurable via the extensions declared in `src/main/resources/META-INF/plugin.xml`. JetBrains may recommend the plugin within related IDE contexts thanks to these extension points (see the <a href="https://plugins.jetbrains.com/docs/intellij/plugin-recommendations.html">Plugin recommendations</a> guide).
- Built against Kotlin 17 and the IntelliJ Platform 2025.1 IC with the Java plugin; compatibility has been validated through build 253.28294.334 so the plugin keeps working on early 2025.2 IDEs. Adjust `build.gradle.kts` to target other baselines if needed.
- Use `./gradlew buildPlugin` to produce the ZIP and `./gradlew runIde` to launch a sandbox. The generated ZIP lives in `build/distributions/`.
