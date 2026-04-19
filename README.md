# Open 100x

<p align="center">
  <img src="docs/open100x-icon.png" alt="Open 100x app icon" width="220">
</p>

Open 100x is an experimental Android camera app focused on extreme zoom workflows, including 100x digital zoom, pro-style controls, local post-processing, stability feedback, histogram tools, flashlight support, and device-specific handling for Pixel landscape stability.

## Beta Release

This repository includes the current beta APK for quick testing.

[Download Open 100x Beta APK](https://github.com/Kenneth-Cho-InfoSec/Open_100x/releases/download/v0.1.0-beta/Open100x-beta.apk)

Because this is a private repository, the download link requires GitHub access to `Kenneth-Cho-InfoSec/Open_100x`.

## Current Beta Notes

- Android app package: `com.example.zoomhundred`
- Release artifact: `release/Open100x-beta.apk`
- Pixel devices are locked to portrait mode for stability.
- Samsung and other non-Pixel devices keep landscape/orientation behavior enabled.
- Processing is local only; no cloud or external API is used.

## Build

```powershell
.\gradlew.bat assembleRelease
```

The generated APK is written to:

```text
app/build/outputs/apk/release/app-release.apk
```

Do not commit local signing keys, `keystore.properties`, `local.properties`, Gradle caches, or generated build output.
