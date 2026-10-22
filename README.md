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
implementation("com.github.blipit-io:blipit-android:v0.4.0")
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

## Stuck screens and flows

Some failures never crash: the splash screen keeps spinning, or a sign-in coroutine is cancelled and never returns. Tell Blipit when the app is ready and wrap the flows that must finish:

```kotlin
Blipit.init(this) {
    key = "<public key>"
    project = "<project id>"
    waitForReady = true
}

Blipit.reportReady()

Blipit.startFlow("sign-in")
Blipit.flowStep("sign-in", "google-picker")
Blipit.endFlow("sign-in")
```

With `waitForReady = true`, call `Blipit.reportReady()` once the first usable screen shows (for example when the home screen has its data). On Android, Blipit also reports the next time the app starts when the system ended it while it was on screen for low memory, too much CPU or memory, or a failed start (Android 11 and later); no code is needed for that.

If one does not finish in time, Blipit opens a **Stuck** issue with the flow, the last step, the screen when it is known, how long it waited and the steps before it. Only time on screen counts: while the app is in the background the clock stops, so a sign-in that sends people to a browser is not cut short. Blipit sets the time limits on its side and the SDK picks them up with its settings, so they can change without an app release. Nothing is decided before those settings arrive.

Call `startFlow` when the person starts the action (taps Continue with Google), not when the screen opens. `endFlow` covers every way out, including the person cancelling. Flow and step names are sent as you write them: use fixed names such as `sign-in` or `google-picker`, never emails, phone numbers or other personal data.

## Store declarations

What to declare when you ship an app with Blipit:

- **Google Play**: in the Data safety form declare App info and performance, Crash logs and Diagnostics: collected, not shared, processed in transit with encryption, not optional, for App functionality and Analytics. Blipit adds no permissions, reads no Android ID, IMEI or advertising id, and starts no WorkManager job or foreground service. If you call `setUser`, also declare User IDs or Email address.
- **Huawei AppGallery**: the Android path needs neither Google Play services nor HMS Core. In your privacy statement, list crash and diagnostic data sent to Blipit (Louward Labs FZC LLC) for app stability, and the same items in the AppGallery Connect privacy section.

Session replay, when you turn it on, is covered in its own section and adds screen content to these declarations.

## Performance

Set `tracesSampleRate = 0.2` in `init` and activity loads and requests show up on the Performance page.

Docs: https://docs.blipit.io/mobile
