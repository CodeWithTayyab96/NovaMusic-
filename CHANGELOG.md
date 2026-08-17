# Changelog

All notable changes to **NovaMusic** are documented here. NovaMusic is a fork
of [OpenTune 3.0.6](https://github.com/Arturo254/OpenTune) (InnerTune /
ArchiveTune lineage) and keeps an independent version history starting at
1.0.0.

The format follows [Keep a Changelog](https://keepachangelog.com/), and this
project adheres to [Semantic Versioning](https://semver.org/).

---

## [1.0.0] — 2026-08-17

Initial NovaMusic release — a rebranded, rebuilt fork of OpenTune 3.0.6.

### Rebrand & identity

- Renamed the app from **OpenTune** to **NovaMusic** (new launcher name,
  logo, monochrome/notification icon, and launcher icons across all
  densities).
- Migrated the package from `com.arturo254.opentune` to
  `com.novamusic.app`; Room database schema snapshots relocated to
  `com.novamusic.app.db.InternalDatabase` so auto-migrations keep working.
- Reset version history: `versionName 1.0.0`, `versionCode 1` (independent
  of the inherited OpenTune 3.0.6 / 133 numbering).
- Removed dead `OpenTune_canvas` translation strings left over from the
  rebrand that were failing release builds (`lintVital` `ExtraTranslation`).

### Real local-file download system (replaces Media3 download-to-disk)

- New `playback/LocalFileDownloader.kt` — `@Singleton` downloader that
  streams the raw audio and saves it as a **real file** via MediaStore under
  `Music/NovaMusic/`, setting `isLocal = true` + `localPath` on the
  `SongEntity`. Exposes `progress: StateFlow<Map<String, LocalDownloadState>>`
  with throttled updates (max every 250 ms or 2% progress).
- New `playback/LocalFileDownloadWorker.kt` — WorkManager `CoroutineWorker`
  (Hilt `@EntryPoint`) so downloads survive app close/kill.
- New `playback/DownloadNotificationManager.kt` +
  `playback/DownloadCancelReceiver.kt` — foreground notification with
  per-song progress, a cancel action, and Spotify-style grouping of multiple
  active downloads into one summary notification.
- Download robustness:
  - Reuses the app's interceptor-equipped `mediaOkHttpClient` instead of a
    bare client (fixes broken/corrupt files from missing YouTube CDN
    headers).
  - Rejects non-audio HTTP responses (HTML/JSON error pages) before writing
    anything.
  - Verifies byte completeness against `Content-Length` — truncated
    downloads are failed and rolled back, not marked complete.
  - Detects the real audio container from the first bytes (WebM `0x1A45DFA3`,
    M4A `ftyp`, Ogg, FLAC, WAV, MP3) and maps the file extension from the
    *container* (`webm`/`m4a`/…), not the codec — fixes files that ExoPlayer
    played but strict external players (VLC) rejected (e.g. WebM+Opus saved
    as `.opus`).
  - Startup `purgeCorruptedLocalFiles()` cleans up previously-marked-local
    files that are actually corrupt/empty.
- UI: download queue screen, per-song badges, and batch menu
  status/remove actions now read from `LocalFileDownloader.progress`; the
  Media3 `DownloadUtil`/`ExoDownloadService` remains only for the streaming
  cache.

### Self-hosted update system

- Repointed `utils/Updater.kt` (releases list, release-by-tag, commit
  history, APK download URLs) from `Arturo254/OpenTune` to
  `CodeWithTayyab96/NovaMusic-`.
- Removed the nightly/beta update channel (the previous R2/CDN endpoint was
  not ours and is unreachable); updates are stable GitHub Releases only.
- Release assets are published as `app-universal-release.apk`, matching the
  updater's expected asset name.

### Release signing & CI/CD

- Wired the `release` `signingConfig` (keystore + `STORE_PASSWORD` /
  `KEY_ALIAS` / `KEY_PASSWORD` env vars) into the release build type so
  `assembleUniversalRelease` produces a signed APK.
- New workflows:
  - `release-build.yml` — manual release pipeline: decode `KEYSTORE_BASE64`
    keystore, build signed release APK, publish a GitHub Release (tag +
    asset), then delete the decoded keystore from the runner.
  - `generate-keystore.yml` — one-time `keytool` keystore generation helper.
- `build.yml` — full-build job now decodes the signing keystore too (release
  variants require it), and runs with `--no-parallel --max-workers=2` to fix
  `OutOfMemoryError: GC overhead limit exceeded` during release-variant
  compilation.
- Added Room ProGuard keep rules (`RoomDatabase`, `@Entity`,
  `AutoMigrationSpec`) for the minified release build.
- Gitignored `app/keystore/` and keystore files so the signing keystore can
  never be committed.

### Known follow-ups

- The Together (collaborative listening) feature is disabled: it previously
  resolved its server from a file hosted on the upstream repo. Will be
  re-enabled when NovaMusic hosts its own endpoint.
- Discord RPC app-icon and default activity-button URLs were repointed to
  this repository's assets.
- Remaining upstream references are limited to license/attribution headers,
  which are intentionally preserved under GPLv3.

---

## Older history

Versions **prior to 1.0.0** belong to the upstream
[OpenTune](https://github.com/Arturo254/OpenTune) project (up to 3.0.6) and
its own changelog applies.
