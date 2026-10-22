# blipit-android

Blipit error monitoring for Android. Know your app broke before your users tell you.

## Install

Add JitPack to `settings.gradle.kts` and the dependency to the app module:

```kotlin
dependencyResolutionManagement {
    repositories {
        maven("https://jitpack.io")
    }
}
```

```kotlin
implementation("com.github.blipit-io:blipit-android:v0.3.1")
```

## Quick start

Call `init` once, in `Application.onCreate`.

```kotlin
import io.blipit.android.Blipit

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Blipit.init(this) {
            key = "<public key>"
            project = "<project id>"
            environment = "production"
            release = "myapp@1.4.2"
        }
    }
}
```

Both values are on the project's Keys page at app.blipit.io. Crashes, ANRs and uncaught exceptions are reported on their own. From Java, pass a `BlipitOptions` instance to `Blipit.init(context, options)`.

## Manual capture

```kotlin
Blipit.captureException(err)
Blipit.captureMessage("cache miss rate above 50%", SentryLevel.WARNING)
Blipit.setUser(id = "42", email = "ana@example.com")
Blipit.setTag("region", "ap-southeast-1")
Blipit.addBreadcrumb("opened checkout", category = "ui")
Blipit.captureSecurity("login_failed", "ana@example.com", ip = "10.0.0.1")
Blipit.flush()
```

## Stuck on startup

An app that never gets past its splash or loading screen does not crash or throw, so nothing is reported on its own. Call `reportReady` once the first real screen can be used:

```kotlin
Blipit.addBreadcrumb("session restored", category = "startup")
Blipit.reportReady()
```

From the first activity start, Blipit counts the time the app is in the foreground (time in the background is not counted). If `reportReady` has not been called after 20 seconds, it sends one warning for that launch: `App did not become ready within 20 s`, with the tags `startup.stalled=true`, `startup.deadline_ms` and `startup.screen` (the activity on screen) and the breadcrumbs added so far. Every such launch is grouped into one Stuck on startup issue. A later `reportReady` adds an `App became ready after N ms` breadcrumb instead of a second event, and also calls Sentry's `reportFullyDisplayed`, so apps with time to full display tracing on get the measurement too. Calling it more than once is fine.

Change the deadline, or turn the check off with 0:

```kotlin
Blipit.init(this) {
    key = "<public key>"
    project = "<project id>"
    startupDeadlineMs = 30_000
}
```

## Bug reports

Show the floating Report a bug button on every screen, or open the reporter from your own menu:

```kotlin
Blipit.showReportButton()
Blipit.showReport()
```

The reporter asks first: Take a screenshot or Skip. The screenshot opens full screen with the tools above and below it; pinch or double-tap to zoom, mark the problem, then add a title and what happened and send. Reports arrive under Bug reports at app.blipit.io. Screen recording is not offered on Android, because it needs a foreground service that Google Play asks every app to declare and justify.

## Session replay

Replay follows the project's Session replay setting in the dashboard (Off, On errors and bug reports, or All visits, sampled). Nothing is recorded until the person using the app agrees:

```kotlin
Blipit.setReplayConsent(true)
Blipit.setReplayConsent(false)
Blipit.hasReplayConsent()
```

Consent is off by default and stored on the device. Turning it off stops recording at once and discards anything kept in memory.

- About one frame per second of the app's own screens, encoded as a small H.264 video, plus taps and screen changes. Only while the app is in the foreground; nothing from other apps or the system UI.
- All text, images, text inputs, WebViews and video players are masked on the device before anything is stored. Mask more views with `android:tag="sentry-mask"`.
- In errors mode the last 30 seconds stay in memory and are uploaded only with an error or a bug report. A bug report sent with `Blipit.showReport()` includes the replay.
- No AccessibilityService, no MediaProjection, no screen-capture prompt, no Google Play services, so it also works on Huawei devices. Needs Android 8.0 (API 26) or newer.
- `replayLabels = true` in `init` keeps labels and button text readable on View-based screens; inputs and images stay masked, and Jetpack Compose screens stay fully masked.
- Apps for children: set `childDirected = true` in `init`, or call `Blipit.disableReplayForChildApp()`, and replay never starts.

Turning the project setting off stops recording on every device, live and on the next launch.

## Performance

Set `tracesSampleRate = 0.2` in `init` and activity loads and requests show up on the Performance page.

Docs: https://docs.blipit.io/mobile
