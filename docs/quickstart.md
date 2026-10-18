---
description: Add simpledatalogs to an Android app with the Kotlin SDK, with launches, sessions, crashes and screen views captured by one argument.
---

# Android (Kotlin)

## Install

In your app module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.xkdlabs:simpledatalogs:0.1.0")
}
```

It comes from Maven Central, needs minSdk 21, and adds the `INTERNET` permission for you.

## Add it

```kotlin
import com.xkdlabs.simpledatalogs.Autocapture
import com.xkdlabs.simpledatalogs.SimpleDataLogs

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SimpleDataLogs.configure(this, ingestKey = "sdl_pub_…", autocapture = Autocapture.ALL)
    }
}

// Anywhere, from any thread:
SimpleDataLogs.track("signup", mapOf("plan" to "pro"))
```

Register `MyApplication` with `android:name` on the `<application>` tag, and ship a public `sdl_pub_` key.

## Send everything, or only what you choose

- **Everything:** `autocapture = Autocapture.ALL` sends launches, sessions, crashes and screen views.
- **Pick:** `autocapture = Autocapture.SESSIONS + Autocapture.CRASHES`.
- **Only your own events:** leave `autocapture` out.

Compose destinations and fragments are not activities, so send their screen views yourself:

```kotlin
SimpleDataLogs.trackScreen("Settings")
```

## In the first minute

- `session_start` and `app_launched` (with `is_first_launch`) as soon as the first activity starts.
- Every event carries a `session_id`; `session_end` (with `duration_s`) follows once the app has been in the background 30 minutes.
- A `screen_view` each time an activity resumes.
- After an uncaught exception, `app_crashed` with `exception_name`, `reason` and `stack` on the next launch.
- Your events within 30 seconds, and at once when the app goes to the background.

## Autocapture defaults

| Option | With `Autocapture.ALL` |
|---|---|
| `APP_LAUNCHES` | on |
| `SESSIONS` (activities started and stopped) | on |
| `CRASHES` (uncaught JVM exceptions) | on |
| `SCREEN_VIEWS` (activity resumed, `screen` = class name) | on |

Native (NDK) crashes and ANRs are not captured. Events wait in a crash-safe outbox on disk until a `202`, so nothing is lost if the process dies. Everything else, including the opt-out, WorkManager and the JVM server client, is in the [Android SDK README](https://github.com/XKD-Labs/simpledatalogs-android#readme).
