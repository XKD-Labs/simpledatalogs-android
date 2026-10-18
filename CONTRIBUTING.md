# Contributing

Thanks for helping. Bug reports and pull requests are welcome; for anything security related, follow [SECURITY.md](SECURITY.md) instead.

## Layout

| Module | Artifact | What |
|---|---|---|
| `simpledatalogs-core` | `com.xkdlabs:simpledatalogs-core` (JAR) | Pure Kotlin/JVM: wire format, outbox, retries, sessions, crash records, and `SimpleDataLogsClient` for servers |
| `simpledatalogs` | `com.xkdlabs:simpledatalogs` (AAR) | The Android facade `SimpleDataLogs`, lifecycle flushes and autocapture wiring |

The core must stay free of runtime dependencies besides the Kotlin stdlib, and must run on Android minSdk 21: no `java.time`, no `java.nio.file`, no Java 8 stream or `Map.getOrDefault` APIs.

## Build and test in Docker

You need only Docker; no JDK, Gradle or Android SDK on the host. Run these from the repository root.

JVM core tests (fast, no Android SDK):

```sh
docker run --rm -v "$PWD":/w -w /w -v sdl-gradle-cache:/home/gradle/.gradle gradle:8.14.3-jdk17 ./gradlew -PjvmOnly=true :simpledatalogs-core:build
```

Android library build, unit tests and lint (the image is amd64; on Apple silicon it runs under emulation and takes several minutes):

```sh
docker run --rm --platform linux/amd64 -u root -v "$PWD":/w -w /w -v sdl-gradle-cache-android:/gradle-cache -e GRADLE_USER_HOME=/gradle-cache cimg/android:2025.10.1 ./gradlew assembleRelease :simpledatalogs:testReleaseUnitTest :simpledatalogs:lintRelease
```

Both use the pinned Gradle wrapper (8.14.3, checked by SHA-256). The named volumes cache Gradle downloads between runs.

Secret scan before you push:

```sh
docker run --rm -v "$PWD":/repo zricethezav/gitleaks:v8.28.0 git /repo -v
```

## Code style

- KDoc on every class and public function; a one-line `//` comment above every private function and above each step of a longer function.
- Self-describing names; booleans start with `is`, `has`, `should` or `can`.
- Every public failure path logs and returns; no public method throws.
- Commits follow [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/), e.g. `fix(core): …`.

## Releasing

Releases go to Maven Central through the Sonatype Central Portal, from `.github/workflows/publish.yml`.

1. Set `VERSION_NAME` in `gradle.properties` and move the `CHANGELOG.md` entry from "Unreleased" to a date.
2. Commit, then push a tag `v<VERSION_NAME>`, e.g. `v0.1.0`. The workflow refuses a tag that does not match `VERSION_NAME`.

The workflow needs these repository secrets:

| Secret | What | Passed to Gradle as |
|---|---|---|
| `MAVEN_CENTRAL_USERNAME` | The user name of a Central Portal user token | `ORG_GRADLE_PROJECT_mavenCentralUsername` |
| `MAVEN_CENTRAL_PASSWORD` | The password of that token | `ORG_GRADLE_PROJECT_mavenCentralPassword` |
| `SIGNING_KEY` | The ASCII-armored GPG private key (`gpg --armor --export-secret-keys <key id>`) | `ORG_GRADLE_PROJECT_signingInMemoryKey` |
| `SIGNING_KEY_PASSWORD` | The key's passphrase | `ORG_GRADLE_PROJECT_signingInMemoryKeyPassword` |

Signing keys and tokens come only from these secrets or your own `~/.gradle/gradle.properties`; never commit them. Publications are signed only when `signingInMemoryKey` is set, so local `publishToMavenLocal` works without a key.
