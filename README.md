# blipit-android

Blipit error monitoring for Android, built on blipit-nuggets, Blipit's own crash-reporting core. Know your app broke before your users tell you.

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
implementation("com.github.blipit-io:blipit-android:v0.5.0")
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

Both values are on the project's Keys page at app.blipit.io. From Java, pass a `BlipitOptions` instance to `Blipit.init(context, options)`.

Reported on their own:

- Uncaught exceptions on any thread, the main thread included. The report is written to disk before the app dies and sent on the next launch.
- ANRs: a watchdog thread pings the main thread and reports when it stays blocked for 5 seconds (`anrTimeoutMs`) while the app is on screen, with the main thread's stack. Off with `anr = false`.
- Native (NDK) crashes: a signal handler (SIGSEGV, SIGABRT, SIGBUS, SIGFPE, SIGILL, SIGTRAP) writes the signal, thread, registers, the raw stack addresses and the loaded libraries, and the report is sent on the next launch. Off with `nativeCrashes = false`.
- Sessions for release health: a session starts when the app comes to the foreground and ends after 30 seconds in the background; crashes mark it crashed, a process that died without a crash marks it abnormal. Off with `sessions = false`.
- Breadcrumbs: screen changes and activity lifecycle, foreground and background, taps (view id or class, never the text), low memory.
- Device context: Android version, device model and screen size.

Events wait in an offline queue under the app's `no_backup` folder (at most 100) and are sent when the network is back. When ingest answers 429 the SDK pauses for `Retry-After` seconds; server errors are retried with backoff.

## Manual capture

```kotlin
Blipit.captureException(err)
Blipit.captureMessage("cache miss rate above 50%", Level.WARNING)
Blipit.setUser(id = "42", email = "ana@example.com")
Blipit.setTag("region", "ap-southeast-1")
Blipit.setExtra("cart_items", 3)
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
- All text, images, text inputs, WebViews and video players are masked on the device before anything is stored. Mask more views with `android:tag="blipit-mask"`.
- In errors mode the last 30 seconds stay in memory and are uploaded only with an error or a bug report. A bug report sent with `Blipit.showReport()` includes the replay.
- Frames are copied from the app's own window with PixelCopy, masked, kept as small JPEGs in memory and encoded on the device with MediaCodec. No AccessibilityService, no MediaProjection, no screen-capture prompt, no Google Play services, so it also works on Huawei devices. Needs Android 8.0 (API 26) or newer.
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

Set `tracesSampleRate = 0.2` in `init` and activity loads (from create to the first frame after resume) show up on the Pulse page.

## Upgrading to 0.5

0.5.0 no longer contains any third-party crash library; everything runs on blipit-nuggets. Most apps only bump the version. Source changes, if your code used them:

- The level enum of the old wrapped library becomes `io.blipit.android.Level` (same names: `FATAL`, `ERROR`, `WARNING`, `INFO`, `DEBUG`).
- `captureException`, `captureMessage` and `captureSecurity` return the event id as a `String?` instead of an id object.
- `BlipitOptions.configure`, `Blipit.dsn(...)` and `Blipit.apply(...)` are gone. There is no DSN any more: the SDK sends the project key in `X-Blipit-Key` to `/api/v1/events`.
- The replay mask tag is `android:tag="blipit-mask"`. Views tagged with the old tag are no longer masked by tag, so rename them (text, images, inputs, WebViews and video stay masked either way).
- Remove the auto-init meta-data line that 0.3 asked for from your manifest if you added it; it does nothing now.
- The release defaults to `<package>@<versionName>+<versionCode>`, the same value as before, so issues and release health continue on the same release.

## What is sent (Google Play Data safety)

| Data | When | Data safety category |
| --- | --- | --- |
| Crash and error reports: exception type, message, stack frames; for native crashes the signal, registers and library paths | Every crash, ANR or `captureException` | App info and performance: Crash logs |
| Breadcrumbs: screen names, lifecycle, taps (view id or class), your own `addBreadcrumb` text | Attached to each report | App info and performance: Diagnostics; App activity: App interactions |
| Device: Android version, device model, screen size; app release and environment | Every report and session | App info and performance: Diagnostics |
| Session: random session id, random install id (created on first launch, removed with the app), start time, status, error count | Start and end of each session | App info and performance: Diagnostics |
| `setUser` id, email, username | Only if your code calls it | Personal info: User IDs, Email address, Name |
| Activity load times | Only with `tracesSampleRate > 0` | App info and performance: Other app performance data |
| Session replay video, masked on the device, plus taps and screen changes | Only after `setReplayConsent(true)` and with replay on for the project | App activity: App interactions; App info and performance: Diagnostics |
| Bug reports: screenshot, title, text, optional email | Only when the user sends one | Messages: Other in-app messages; Photos; Personal info: Email address if given |

Not collected: Android ID, IMEI, advertising id, location, contacts, IP address on the device (the server sees the connection address and the project decides whether to keep it), text typed into fields. All traffic is HTTPS to the endpoint you set (`https://in.blipit.io` by default). Data is encrypted in transit and can be deleted on request through the project.

## Store checklist

Google Play:

- [ ] Data safety form filled from the table above (Crash logs, Diagnostics; Personal info only if you call `setUser` with an email or name).
- [ ] Permissions the library adds: `INTERNET` and `ACCESS_NETWORK_STATE`, both normal permissions. No restricted or dangerous permission, no `QUERY_ALL_PACKAGES`, no foreground service, no AccessibilityService, no MediaProjection.
- [ ] No background service or job is added for monitoring: reports are sent from a daemon thread while the app runs and on the next launch.
- [ ] No hardware identifiers: no Android ID, IMEI, MAC or serial; the install id is random and app-scoped.
- [ ] Native library `libblipit-nuggets.so` is built with 16 KB page alignment (Android 15+ requirement) for arm64-v8a, armeabi-v7a, x86 and x86_64.
- [ ] Apps for children (Families policy): set `childDirected = true` so replay never starts; consider not calling `setUser`.
- [ ] Session replay: ask the user before `setReplayConsent(true)` and mention it in your privacy policy.

Huawei AppGallery:

- [ ] Works without Google Play services and without HMS: the dependency tree is the Kotlin standard library, the Blipit JVM core and the Android platform.
- [ ] Same permissions as above, nothing Huawei-specific to declare.
- [ ] Privacy statement lists crash logs, diagnostics and, if used, replay and user identifiers, matching the table above.
- [ ] The endpoint is reachable from the regions you publish to (`in.blipit.io` over HTTPS, port 443).

Docs: https://docs.blipit.io/mobile
