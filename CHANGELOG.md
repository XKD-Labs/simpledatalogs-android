# Changelog

All notable changes to the SimpleDataLogs Android and Kotlin SDK. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the SDK uses [Semantic Versioning](https://semver.org/).

## [0.1.0] - Unreleased

The first public release.

### Added

- `com.xkdlabs:simpledatalogs`, an Android library (minSdk 21): `SimpleDataLogs.configure`, `track`, `trackScreen`, `setDeviceId`, `setDefaultProperties`, `setEnabled`, `flush` and `flushBlocking`.
- Apps send a partial batch 10 s after its first event, or 5 s while debuggable, so new events show within seconds.
- `com.xkdlabs:simpledatalogs-core`, pure Kotlin/JVM with no dependencies besides the Kotlin stdlib, with `SimpleDataLogsClient` for servers (5 s batches).
- A crash-safe disk outbox with batching, gzip, retries with jittered backoff, `Retry-After`, 401 pauses, 413 splitting and 415 set-asides.
- Bounded storage: at most 10,000 events, 10 MB or 500 batch files, kept out of Android Auto Backup.
- A flush when the app goes to the background, and `flushBlocking` for WorkManager.
- Opt-in autocapture of launches, sessions, crashes and screen views.
- `SimpleDataLogs.installReferrer` reads the Play Store install referrer for installs from shared links, through Google's library when the app includes it.
