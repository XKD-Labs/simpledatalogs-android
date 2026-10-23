# SimpleDataLogs for Android

The Android and Kotlin SDK for [simpledatalogs](https://simpledatalogs.com): track events from Android apps and JVM servers, and see them as counters and charts.

- Android 5.0+ (minSdk 21), Kotlin or Java.
- No dependencies besides the Kotlin standard library: no AndroidX, no OkHttp, no JSON library.
- Two artifacts: `com.xkdlabs:simpledatalogs` (the Android library) and `com.xkdlabs:simpledatalogs-core` (pure Kotlin/JVM, for servers).
- Follows every [sender rule](https://simpledatalogs.com/docs/api/sender-rules) of the ingest API.
- Full guide: [simpledatalogs.com/docs](https://simpledatalogs.com/docs).

## Install

Early access: 0.1.0 isn't on Maven Central yet. Until it is, run `./gradlew publishToMavenLocal` in a clone and add `mavenLocal()` to your repositories. Then, in your app module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.xkdlabs:simpledatalogs:0.1.0")
}
```

Make sure `mavenCentral()` is among your repositories (it is in every new Android project). The library's manifest adds the `INTERNET` permission.

## Quick start

```kotlin
import com.xkdlabs.simpledatalogs.SimpleDataLogs

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // The opt-out first, then identity and default properties, then the key.
        SimpleDataLogs.setEnabled(userAllowsUsageStatistics)
        SimpleDataLogs.setDeviceId(anonymousDeviceId)
        SimpleDataLogs.setDefaultProperties(mapOf("app_version" to BuildConfig.VERSION_NAME, "distribution" to "play"))
        SimpleDataLogs.configure(this, ingestKey = "sdl_pub_…")
    }
}

// Anywhere, from any thread; it returns at once.
SimpleDataLogs.track("level_complete", mapOf("level" to 3, "is_pro" to true))
```

Register `MyApplication` with `android:name` on the `<application>` tag. Ship a public `sdl_pub_` key in apps; secret `sdl_sec_` keys are for servers.

To send to another ingest endpoint, such as a local one:

```kotlin
SimpleDataLogs.configure(this, ingestKey = "sdl_pub_…", endpoint = "http://10.0.2.2:4001")
```

From Java, every method is static: `SimpleDataLogs.track("signup", Collections.singletonMap("plan", "pro"));`.

## Autocapture

One argument sends launches, sessions, crashes and screen views without any `track` call. It is off until you pass it.

```kotlin
SimpleDataLogs.configure(this, ingestKey = "sdl_pub_…", autocapture = Autocapture.ALL)
```

| Option | What you get |
|---|---|
| `Autocapture.APP_LAUNCHES` | `app_launched` once per process start that shows UI, with `is_first_launch` on the very first |
| `Autocapture.SESSIONS` | `session_start`, a `session_id` on every event, and `session_end` (`duration_s`) once the app has been in the background for 30 minutes |
| `Autocapture.CRASHES` | An uncaught exception is saved at the crash and sent as `app_crashed` (`exception_name`, `reason`, `stack`) on the next launch; your existing crash reporter still runs |
| `Autocapture.SCREEN_VIEWS` | `screen_view` with `screen` = the activity's class name each time an activity resumes |

`Autocapture.ALL` is all four; combine with `+` to pick, e.g. `Autocapture.SESSIONS + Autocapture.CRASHES`.

- **Screen views by hand:** `SimpleDataLogs.trackScreen("Settings")` sends `screen_view` with a `screen` prop. Use it for Compose destinations or fragments, which activity callbacks cannot see.
- A rotation is not a trip to the background, so it neither flushes nor touches the session.
- Native (NDK) crashes and ANRs are not captured.
- The opt-out applies: with `setEnabled(false)`, nothing is captured and a saved crash is discarded.

## API

| Call | What it does |
|---|---|
| `configure(context, ingestKey, endpoint, autocapture)` | Starts the SDK once; `autocapture` defaults to `Autocapture.NONE`, `endpoint` to `https://edge.simpledatalogs.com`. Later calls are ignored with a warning. |
| `track(eventName, properties)` | Records one event. `event_id` and `ts` are stamped now, when the event happens. |
| `trackScreen(screenName, properties)` | Records `screen_view` with a `screen` prop. |
| `setDeviceId(deviceId)` | Sets (or clears, with `null`) the `device_id` on every later event. |
| `setDefaultProperties(properties)` | Replaces the properties merged into every event's `props`; the event's own properties win on a clash. |
| `setEnabled(isEnabled)` | The opt-out. `false` ignores new events and **deletes every unsent event**; `true` collects again. |
| `flush()` | Seals and starts sending everything now, without waiting. |
| `flushBlocking(timeoutMillis)` | Sends everything and waits; returns `true` when nothing is left to send. For background work only; on the main thread it returns `false` at once. |
| `installReferrer(context, callback)` | Reads the Play Store install referrer once and calls back on the main thread with it, or `null`. Needs `com.android.installreferrer:installreferrer` in your app. |

Property values are `String`, `Int`, `Long`, `Short`, `Byte`, `Float`, `Double` or `Boolean`. Anything else (`null`, lists, maps, objects), and NaN or infinity, is dropped with a log line.

Call `setEnabled` **before** `configure`. A user who opted out then never sends a byte, and whatever an earlier launch left on disk is deleted instead of sent.
The opt-out is not persisted by the SDK; your app's own setting is the source of truth, so pass it on every launch.

## Installs from shared links

Put a random share id in the Play Store link's referrer (`&referrer=share_id%3DK7F2mQ9x`). On the first launch, read it and send it on `app_installed`; SimpleDataLogs then writes the share id onto that event. Add `com.android.installreferrer:installreferrer:2.2` to your app first.

```kotlin
SimpleDataLogs.installReferrer(context) { installReferrer ->
    SimpleDataLogs.track("app_installed", mapOf("install_referrer" to installReferrer))
}
```

A `null` referrer is dropped from the props, so the event still counts as an install.

## Sending from WorkManager

The SDK flushes when the app goes to the background, and resends anything left on the next launch. To also deliver while the app stays closed, flush from a worker. The SDK does not depend on WorkManager; add `androidx.work:work-runtime-ktx` yourself.

```kotlin
class FlushEventsWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    // Application.onCreate already configured the SDK in this process.
    override fun doWork(): Result {
        return if (SimpleDataLogs.flushBlocking(20_000)) Result.success() else Result.retry()
    }
}

// In Application.onCreate, after configure:
val flushRequest = PeriodicWorkRequestBuilder<FlushEventsWorker>(6, TimeUnit.HOURS)
    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
    .build()
WorkManager.getInstance(this).enqueueUniquePeriodicWork("simpledatalogs-flush", ExistingPeriodicWorkPolicy.KEEP, flushRequest)
```

## Servers and other JVM apps

`simpledatalogs-core` is pure Kotlin/JVM (Java 8 bytecode) with the same outbox and rules. Servers flush every 5 seconds.

```kotlin
dependencies {
    implementation("com.xkdlabs:simpledatalogs-core:0.1.0")
}
```

```kotlin
import com.xkdlabs.simpledatalogs.SimpleDataLogsClient

val analytics = SimpleDataLogsClient(
    ingestKey = System.getenv("SIMPLEDATALOGS_KEY"),
    storageDirectory = File("/var/lib/myapp/simpledatalogs"),
)

analytics.track("invoice_paid", mapOf("plan" to "pro", "amount_cents" to 4_900))

// On shutdown: a last flush of up to 5 s; undelivered events stay on disk for the next start.
Runtime.getRuntime().addShutdownHook(Thread { analytics.close() })
```

Give each process its own `storageDirectory`. The client's threads are daemons, so it never keeps a JVM alive. Log lines go to `java.util.logging` under the name `simpledatalogs`.

## Privacy

- The SDK sends only the events you track, plus the autocapture events you turn on. It reads no advertising id, Android id, location, contacts or installed apps.
- `device_id` is sent only if you set one; use an anonymous id you generate, not a personal identifier.
- The outbox lives in `noBackupFilesDir/simpledatalogs/`, so Android Auto Backup never copies unsent events.
- `setEnabled(false)` deletes every unsent event from disk.
- Crash reports hold the exception class, its message and up to 40 stack frames. If your exception messages can contain personal data, leave `Autocapture.CRASHES` off.

## How it behaves

- **Batching.** Events go out in batches of 50, or 30 s after the first event of a partial batch (5 s for the JVM client). Every request is under 256 KB, and bodies of 1 KB or more are gzipped.
- **Crash-safe outbox.** Each event is appended to a journal on disk as it is tracked.
  - Batches are sealed into files and deleted only after a `202`.
  - The next launch resends anything left over, including a journal cut short by a crash.
  - A batch file that fails to read three times in a row is renamed `*.unreadable` and skipped, so it cannot block newer batches.
- **Bounded.** The outbox keeps at most 10,000 events, 10 MB or 500 batch files. Past that, the oldest batches are dropped, with a log line. While a backoff or a 401 pause holds sending, the batch timer seals nothing, so days offline never leave one file per event.
- **Never outruns the disk.** If more than 5,000 events wait to be journaled (a hot loop faster than the disk), new ones are skipped, with one log line, until it catches up.
- **Off the main thread.** `track` stamps the event and hands it to one background thread; disk writes and HTTP requests never run on the caller's thread.
- **`415`** sets the batch aside as `*.unsupported` (never resent unchanged) and the next batch goes.
- **Retries.** Timeouts, network errors, `429`, `503` and any other non-`202` answer are retried with exponential backoff and full jitter, from 1 s up to 5 min, never sooner than 250 ms. `Retry-After` is honored, as seconds or an HTTP-date.
- **Bad key (`401`).** Sending pauses and one configuration error is logged; no event is lost.
  - The SDK re-probes with its smallest batch one minute later, then every 10 minutes, and again on the next launch.
  - Only a `202` or `413` answer ends the pause. A probe lost to the network or a `503` keeps it, and the next probe honors any `Retry-After`.
- **Too large (`413`).** The batch is split in half and resent. A single event that is still too large is dropped, with a log line.
- **Lifecycle.** The SDK flushes when the last activity stops (the app goes to the background). Anything a flush cannot deliver in time stays on disk for the next launch.
- **Fail open.** No public method throws, and nothing crashes or blocks the host app. Problems are logged to Logcat under the tag `simpledatalogs`.

Watch the logs with:

```sh
adb logcat -s simpledatalogs
```

## Development

Everything builds in Docker; see [CONTRIBUTING.md](CONTRIBUTING.md) for the exact commands.

## Links

- Docs: https://simpledatalogs.com/docs
- Changelog: [CHANGELOG.md](CHANGELOG.md)
- Security: [SECURITY.md](SECURITY.md)
- License: [MIT](LICENSE)
