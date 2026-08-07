# OpenTune

<div align="center">
  <img src="https://github.com/Arturo254/OpenTune/blob/master/fastlane/metadata/android/en-US/images/featureGraphic.png" alt="OpenTune Banner" width="100%"/>

  ### Advanced YouTube Music client with Material Design 3 for Android

  [![Latest Release](https://img.shields.io/github/v/release/Arturo254/OpenTune?style=flat-square&logo=github&color=0D1117&labelColor=161B22)](https://github.com/Arturo254/OpenTune/releases)
  [![License](https://img.shields.io/github/license/Arturo254/OpenTune?style=flat-square&logo=gnu&color=2B3137&labelColor=161B22)](https://github.com/Arturo254/OpenTune/blob/main/LICENSE)
  [![Translation Status](https://badges.crowdin.net/opentune/localized.svg)](https://crowdin.com/project/opentune)
  [![Android](https://img.shields.io/badge/Platform-Android%208.0+-3DDC84.svg?style=flat-square&logo=android&logoColor=white&labelColor=161B22)](https://www.android.com)
  [![CI](https://img.shields.io/badge/CI-GitHub%20Actions-2088FF?style=flat-square&logo=githubactions&logoColor=white&labelColor=161B22)](.github/workflows/build.yml)
</div>

---

## Table of Contents

- [Overview](#overview)
- [Key Features](#key-features)
- [Technology Stack](#technology-stack)
- [Project Structure](#project-structure)
- [Installation](#installation)
- [Building from Source](#building-from-source)
- [Continuous Integration](#continuous-integration)
- [Contributing](#contributing)
- [Support the Project](#support-the-project)
- [Acknowledgments](#acknowledgments)
- [License](#license)

---

## Overview

**OpenTune** is an open-source YouTube Music client designed for Android devices. It delivers a superior user experience with a modern interface built on **Jetpack Compose** and **Material Design 3**, offering advanced features to explore, play, and manage music without the limitations of the official application.

The app is developed as a multi-module Kotlin project using **Media3 / ExoPlayer** for playback, **Hilt** for dependency injection, **Room** for local storage, and **Jetpack Compose** for the UI.

### Key Benefits

- **Ad-free experience** — no advertising interruptions
- **Background playback** — keep listening while using other apps
- **Privacy-focused** — no data collection or tracking
- **Customizable interface** — dynamic themes and many options
- **Real offline downloads** — songs are saved as actual audio files on your device (in `Music/NovaMusic/`) and survive app restarts

> **Note**: OpenTune is an independent project and is not affiliated with, sponsored by, or endorsed by YouTube or Google.

---

## Key Features

### Core Functionality

| Feature | Description |
|---|---|
| 🎵 **Ad-free playback** | Enjoy music without advertising interruptions |
| 🔄 **Background playback** | Continue listening while using other applications |
| 🔍 **Advanced search** | Quickly find songs, videos, albums, playlists, and artists |
| 📥 **Real file downloads** | Downloads are written to disk via MediaStore (Music/NovaMusic/) as playable audio files, driven by WorkManager so they survive app close/kill |
| 📥 **Download queue** | Live per-song progress (queued / downloading / completed / failed) with cancel and remove actions — downloads run in the background and survive app restarts |
| 📚 **Library management** | Albums, artists, playlists, history, favorites, and statistics — all stored locally in Room |
| 📱 **Offline mode** | Downloaded songs are flagged `isLocal` and the player prefers the local file automatically |
| 💾 **Backup & restore** | Export and import your library and settings |
| 📊 **Stats & Year in Music** | Playback statistics and yearly listening summaries |
| 🔀 **Explore & charts** | Moods & genres, new releases, and charts browsing |

### Audio Enhancements

| Feature | Description |
|---|---|
| 🎤 **Synchronized lyrics** | Synced lyrics from multiple providers (LRC Lib, Kugou, SimpMusic, BetterLyrics, YouTube subtitles, and more) |
| ⚡ **Smart silence skip** | Automatically skips silent segments |
| 🔊 **Volume normalization** | Balances loudness across tracks |
| 🎛️ **Tempo & pitch control** | Adjust playback speed and pitch |
| 🔄 **Crossfade** | Seamless transitions between tracks |
| 🎚️ **Equalizer** | Full equalizer and audio effects |
| ⏱️ **Sleep timer** | Stop playback automatically |

### Personalization & Integration

| Feature | Description |
|---|---|
| 🎨 **Dynamic theming** | The interface adapts to album artwork colors |
| 🌐 **Multi-language support** | 20+ languages maintained on Crowdin |
| 🚗 **Android Auto compatible** | Integration with vehicle infotainment systems |
| 🧩 **Widgets** | Home-screen player widgets (Glance) |
| 🎯 **Material Design 3** | Design aligned with Google's latest guidelines |
| 🖼️ **Artwork export** | Save high-resolution album images |
| 🎬 **Animated artwork** | Animated covers powered by the Apple Music / OpenTune Canvas API — availability varies; check [canvas-opentune.netlify.app](https://canvas-opentune.netlify.app/) |
| 🎧 **Spotify integration** | Import and browse Spotify playlists / library |
| 🎤 **Song recognition** | ShazamKit-based recognition module |
| 🤖 **PoToken support** | Handle YouTube streaming authorization tokens |

---

## Technology Stack

| Layer | Technology |
|---|---|
| Language | Kotlin 2.3.20 |
| UI | Jetpack Compose (1.11.0-rc01) + Material 3 (1.5.0-alpha18) |
| Playback | Media3 / ExoPlayer 1.10.0 (HLS, OkHttp data source) |
| Local storage | Room 2.8.4 + DataStore (Preferences) |
| DI | Hilt 2.59.2 (KSP) |
| Background work | WorkManager 2.11.2 |
| Networking | Ktor 3.4.2, OkHttp 5, NewPipe Extractor |
| Images | Coil 3.4.0 |
| Build | Android Gradle Plugin 9.1.0, Gradle wrapper 9.4.1, JDK 21 |
| Other | Glance widgets, Reorderable, Haze, Squiggly Slider, Timber |

---

## Project Structure

Multi-module Gradle project — the app itself plus library modules:

| Module | Purpose |
|---|---|
| `app` | Main application: UI, playback, database, view models |
| `innertube` | YouTube Music InnerTube API client |
| `spotify` | Spotify API client (playlists, library, playback resolution) |
| `kugou` | Kugou lyrics provider |
| `lrclib` | LRC Lib synced-lyrics provider |
| `lastfm` | Last.fm scrobbling / metadata |
| `betterlyrics` | Additional lyrics provider |
| `kizzy` | Lyrics provider (Kizzy API) |
| `simpmusic` | SimpMusic lyrics provider |
| `canvas` | Animated artwork (Apple Music canvas) support |
| `shazamkit` | Song recognition |
| `jossredconnect` | Streaming / download helper libraries |

### Downloads architecture

Downloads use a dedicated, app-owned pipeline instead of Media3's download-to-disk:

- `playback/LocalFileDownloader.kt` — `@Singleton` downloader; exposes `progress: StateFlow<Map<String, LocalDownloadState>>`; `download(songId, title, artist)` streams the raw audio through the resolver-equipped OkHttp client and saves it to **MediaStore (`Music/NovaMusic/`)**; `deleteLocalFile(songId)` removes the file and resets the entity.
- `playback/LocalFileDownloadWorker.kt` — WorkManager `CoroutineWorker` that fetches the downloader via a Hilt `@EntryPoint`, so downloads continue in the background and survive app close/kill.
- Completed downloads set `isLocal = true` + `localPath` on the `SongEntity`; `Song.toMediaItem()` prefers the local file at playback time.
- The Media3 `DownloadUtil` / `ExoDownloadService` system remains for the streaming cache (player cache) — only the download-to-disk path was replaced.

---

## Installation

### System Requirements

| Component | Requirement |
|:----------|:------------|
| Operating System | Android 8.0 (Oreo, API 26) or higher |
| Network | Internet connection for streaming |
| Storage | Varies with library size; downloaded files live in `Music/NovaMusic/` |

### Installation Methods

#### Option 1: GitHub Releases (Recommended)

1. Go to the [Releases](https://github.com/Arturo254/OpenTune/releases) page on GitHub
2. Download the APK for the latest stable version
3. Enable "Install from unknown sources" in your device's security settings
4. Open the downloaded APK to install

#### Option 2: Official Website

1. Visit the official [OpenTune website](https://opentune.netlify.app/)
2. Select the Android download option
3. Follow the installation instructions provided

#### Option 3: F-Droid

<div align="center">

[<img src="https://f-droid.org/badge/get-it-on.png" alt="Get it on F-Droid" height="80">](https://f-droid.org/packages/com.novamusic.app)

</div>

#### Option 4: OpenApk

<div align="center">

[<img src="https://www.openapk.net/images/openapk-badge.png" alt="Get it on OpenApk" height="80">](https://www.openapk.net/opentune/com.novamusic.app/)

</div>

> **Security notice**: Only install the app from the official channels listed above. Avoid APKs from unverified sources.

---

## Building from Source

### Prerequisites

| Tool | Required |
|---|---|
| JDK | **21** (the project sets `kotlin.jvmToolchain(21)` and `compileOptions` `JavaVersion.VERSION_21`) |
| Android SDK | Platform **API 36** (compileSdk 36, targetSdk 36, minSdk 26) |
| Android Studio | Current stable release (2024.2+ recommended) |
| Gradle | None to install — the **wrapper (9.4.1)** handles the version |

No Gradle installation or version pinning is needed: always use `./gradlew`.

> **Note**: The foojay toolchain auto-provisioning plugin is disabled in `settings.gradle.kts` (for F-Droid compatibility), so your JDK itself must be version 21 — a JDK 17 installation will fail with "No matching toolchains found".

### Build

This project defines **ABI product flavors** — `universal`, `arm64`, `armeabi`, `x86`, `x86_64` — so there is no plain `assembleDebug` task. Use a flavor-specific task:

```bash
# Debug APK containing all ABIs (the common choice for testing)
./gradlew assembleUniversalDebug

# Release (requires signing credentials via STORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD env vars
# and a keystore at app/keystore/release.keystore — that file is not in the repo,
# so create your own for local release builds)
./gradlew assembleUniversalRelease

# Build every flavor × build type, plus unit tests
./gradlew build

# Run unit tests only
./gradlew test

# Clean
./gradlew clean
```

APKs are produced under `app/build/outputs/apk/<flavor>/<buildType>/`, e.g. `app/build/outputs/apk/universal/debug/app-universal-debug.apk`.

### Build from Android Studio

1. Open Android Studio → "Open an existing Android Studio project"
2. Select the project directory and wait for Gradle sync
3. Run ▶ with the `universalDebug` variant selected, or use Build → Build APK(s)

---

## Continuous Integration

The repository includes GitHub Actions workflows in `.github/workflows/`:

- **`build.yml`** — triggered on push to `main`/`master` and manual `workflow_dispatch`:
  - **Build Debug APK** job: `ubuntu-latest`, JDK 21 (Temurin) with Gradle cache, `./gradlew assembleUniversalDebug`, then uploads the APK from `app/build/outputs/apk/universal/debug/` as an artifact.
  - **Full build + unit tests** job: runs `./gradlew build` (all variants + tests). It is intentionally **independent** of the APK job, so test failures never block the APK artifact.
- **`android.yml`** — manual build workflow
- **`pr-debug-build.yml`** — debug build on every pull request

---

## Contributing

### Code of Conduct

All participants must follow our [Code of Conduct](https://github.com/Arturo254/OpenTune/blob/master/CODE_OF_CONDUCT.md), which promotes an inclusive, respectful, and constructive environment.

### Translation

Help translate OpenTune into your language or improve existing translations:

<div align="center">

[![POEditor](https://img.shields.io/badge/POEditor-2196F3?style=for-the-badge&logo=translate&logoColor=white)](https://poeditor.com/join/project/208BwCVazA)
[![Crowdin](https://img.shields.io/badge/Crowdin-2E3440?style=for-the-badge&logo=crowdin&logoColor=white)](https://crowdin.com/project/opentune)

</div>

### Community Channels

<div align="center">

[![Telegram Chat](https://img.shields.io/badge/Telegram-Chat-2CA5E0?style=for-the-badge&logo=telegram&logoColor=white)](https://t.me/OpenTune_chat)
[![Telegram Updates](https://img.shields.io/badge/Telegram-Updates-2CA5E0?style=for-the-badge&logo=telegram&logoColor=white)](https://t.me/opentune_updates)

</div>

### Development Workflow

1. Check [open issues](https://github.com/Arturo254/OpenTune/issues) or create a new one
2. Fork the repository and create a feature branch (`git checkout -b feature/...`)
3. Implement your changes following the project's Kotlin/Compose conventions
4. Run the unit tests and build (`./gradlew test` / `./gradlew build`)
5. Commit with a descriptive message and push to your fork
6. Open a Pull Request referencing the related issue

See [CONTRIBUTING.md](CONTRIBUTING.md) for the full contribution guide.

---

## Support the Project

If OpenTune is valuable to you, consider donating. Support helps with:

- New features and improvements
- Bug fixes and performance optimization
- Infrastructure and maintenance

<div align="center">

[![GitHub Sponsors](https://img.shields.io/badge/GitHub_Sponsors-181717?style=for-the-badge&logo=github&logoColor=white)](https://github.com/sponsors/Arturo254)
[![PayPal](https://img.shields.io/badge/PayPal-00457C?style=for-the-badge&logo=paypal&logoColor=white)](mailto:cervantesarturo254@gmail.com)

</div>

> Donations are completely optional — OpenTune will always be free and open source.

---

## Acknowledgments

Special thanks to:

- **[ArchiveTune](https://github.com/koiverse/ArchiveTune)** — base project and inspiration for many ideas
- **[Vivi Music](https://github.com/vivizzz007/vivi-music)** — source of the Canvas API and inspiration
- **[@Fabito02](https://github.com/Fabito02)** — constant support, feedback, and ideas
- **mostafaalagamy** — MetroList implementation
- **Community translators** — making OpenTune accessible worldwide
- **Beta testers** — improving stability and usability

---

## License

**Copyright © 2025 Arturo Cervantes**

This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but **WITHOUT ANY WARRANTY**; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the [GNU General Public License](https://github.com/Arturo254/OpenTune/blob/main/LICENSE) for more details.

<div align="center">

[![GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg?style=for-the-badge&logo=gnu&logoColor=white)](https://www.gnu.org/licenses/gpl-3.0)

</div>

---

<div align="center">
  <p><strong>© 2023-2025 Open Source Project</strong></p>
  <p>Developed with passion by <a href="https://github.com/Arturo254">Arturo Cervantes</a></p>
</div>
