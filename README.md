# Open 100x

<p align="center">
  <img src="docs/open100x-icon.png" alt="Open 100x app icon" width="220">
</p>

Open 100x is an Android camera app built to give more phones access to extreme 100x zoom-style shooting, even when the stock camera app does not expose that kind of range.

The goal is not just to add a bigger zoom slider. Open 100x is meant to be an open extreme-zoom tool with local image enhancement, pro-style controls, stability feedback, and traditional image processing designed specifically for the messy reality of long-range mobile zoom.

## What It Does

- Unlocks up to 100x zoom through native camera zoom plus digital crop.
- Adds controls and feedback designed for high zoom, including stability status, histogram tools, grid modes, zoom presets, flashlight support, and crop nudging.
- Includes local post-processing for extreme zoom captures.
- Uses traditional, non-AI image processing only.
- Runs fully on-device with no cloud processing and no external APIs.

## Local Upscaling And Denoising

Open 100x includes a local post-processing path for zoomed images. The purpose is to push open, traditional image-processing techniques that can make extreme phone zoom more usable.

The enhancement pipeline may include:

- Denoising
- Smoothing
- Sharpening
- Contrast and detail recovery
- Local upscaling suitable for high-zoom crops

These algorithms are implemented locally in the app and are designed to improve noisy, soft, high-magnification phone images without AI/ML inference.

## Beta Release

The current beta APK is available from the GitHub release page:

[Download Open 100x Beta APK](https://github.com/Kenneth-Cho-InfoSec/Open_100x/releases/download/v0.1.0-beta/Open100x-beta.apk)

## Current Beta Notes

- Android app package: `com.example.zoomhundred`
- Release asset: `Open100x-beta.apk`
- Pixel devices are locked to portrait mode for stability.
- Samsung and other non-Pixel devices keep landscape/orientation behavior enabled.
- Processing is local only; no cloud or external API is used.
- This is a beta build, so device behavior may vary.

## Build

```powershell
.\gradlew.bat assembleRelease
```

The generated APK is written to:

```text
app/build/outputs/apk/release/app-release.apk
```

Do not commit local signing keys, `keystore.properties`, `local.properties`, Gradle caches, or generated build output.
