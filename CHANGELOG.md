## 0.7.2

- Same as 0.7.1, which never built on JitPack (Gradle download timed out); use v0.7.2.

## 0.7.1

- One `StartupFailed` per launch: after the first `captureStartup` or `reportReady(failed)` of a launch, later calls in the same launch send nothing, so one failed start is one issue instead of one per reason. Call `captureStartup` with the most specific error first (for example the Keystore read), before any general reason.

## 0.7.0

- Failed starts: `Blipit.reportReady(failed = "sessionUnreadable")` on your error screen sends one warning `StartupFailed` with that reason and every breadcrumb of the launch, and ends the startup wait; Blipit groups it by reason. `Blipit.captureStartup(error, step = "auth")` sends the same event for a caught exception with its class, message and cause chain, and the Keystore error code when a cause has one (`auth: GeneralSecurityException caused by KeyStoreException 7`). `reportReady()` without a reason works as before. Same names and event shape as the Swift SDK.
- Stuck on startup no longer depends on reaching Blipit first: on a first launch without network the check uses 20 s and 60 s until Blipit's own limits arrive, retried every minute. Stuck reports now carry the app's own breadcrumbs as well as the flow steps.
- Calls made before `init` (`addBreadcrumb`, `setTag`, `captureMessage`, `captureException`, `reportReady(failed)`, `captureStartup`) are kept, up to 100, and applied once `init` runs.
- Previous launch: breadcrumbs are kept on disk (`no_backup/blipit/breadcrumbs.json`) and the last launch's newest 50 ride on the first event of the next launch as `extra.previous_launch`, labelled "previous launch".
- Failed requests are reported on their own through `BlipitInterceptor`, one line on the app's `OkHttpClient.Builder()`: `.addInterceptor(BlipitInterceptor())`. When a request ends with an `IOException` (anything but a cancel) or an HTTP 5xx, Blipit sends an error event `HTTPRequestFailed` with the value `<METHOD> <host><path> failed: <status or exception name>`, for example `GET api.example.com/orders/123 failed: 503` or `... failed: SocketTimeoutException`. The event carries the method, the URL without query, user or fragment, the status, the exception, the duration and the breadcrumbs; never headers or bodies. 4xx answers are left out. Requests to Blipit itself are skipped. OkHttp 4 and 5 both work; OkHttp stays the app's own dependency.
- Every request through the interceptor also leaves an `http` breadcrumb (method, URL without query, status, duration), so crashes and errors show the calls before them. `networkBreadcrumbs = false` turns the breadcrumbs off.
- One event per method, host and path per minute; repeats are counted into the next event (`extra.http.repeated`). Blipit groups every failure of one endpoint into one issue, with ids and UUIDs in the path folded together.
- The switch and the pace come from Blipit (the settings answer, like the Stuck limits); on by default. `httpFailures = false` turns it off in the app.

## 0.6.1

- The report screen no longer sets the status and navigation bar colours with the APIs Android 15 deprecated. On Android 15 and later its navy background fills the area behind the bars; older versions show the system bars in their default colour. Google Play stops warning apps that bundle the SDK about deprecated edge-to-edge APIs.

## 0.6.0

- Shake to report (`shakeToReport = true`, off by default): shaking the phone opens the reporter on the screen the person is on. Uses the accelerometer only while the app is on screen; no permission. One shake opens one reporter, never while one is already open.
- Screenshot to report (`screenshotToReport = true`, off by default): on Android 14 and later, when the person takes a normal screenshot in the app, a small prompt asks "Report a bug with this screen?" (Report / Not now, gone after 6 seconds). Report opens the reporter with Blipit's own capture of the screen. Adds the normal permission `DETECT_SCREEN_CAPTURE`; the SDK only learns that a screenshot happened and never reads the screenshot, the gallery or storage. Does nothing on Android 13 and older.
- Two-finger press and hold (`twoFingerReport = true`, off by default): two fingers held still for about a second anywhere open the reporter. Touches are only observed, never consumed, so the app's own gestures keep working.
- Report after a crash (`reportAfterCrash = true`, off by default): on the first launch after a crash Blipit caught, a prompt asks "The app closed unexpectedly. Tell us what you were doing?". The report is linked to the crash, so it shows on the crash issue and the crash shows on the report.
- Which screen? (`screenPicker`, on by default because it only changes the reporter UI): when the app opens the reporter from code, for example from a Profile menu, Take a screenshot shows the current screen and up to 3 earlier screens to pick from. The earlier screens are small snapshots kept in memory only, with `blipit-mask` views and password fields blacked out, cleared after 3 minutes in the background or when replay consent is withdrawn; only the chosen one is uploaded. Set `screenPicker = false` to skip it.
- Floating button from the dashboard: the project setting Floating button (Off, Everyone, Testers only) decides whether the Report a bug button shows on its own. The SDK sends the id and email from `setUser` and gets back only yes or no; the tester list stays on the server. `showReportButton()` keeps working as before.
- `Blipit.setScreen(name)`: tells Blipit which screen is showing in single-activity apps (Jetpack Compose, fragments). It adds the navigation breadcrumb, marks the screen in session replay and gives the screen picker one snapshot per named screen.
- For the React Native and Flutter bridges: `setReportHandler`, `attach`, `lastCrashId` and `promptAfterCrash`.
- Platform mismatch: when Blipit refuses data because the key belongs to a project for another platform (HTTP 409 platform_mismatch), the SDK logs one clear error and stops sending for the rest of the launch. Bug reports and session replay stop at once; crash reports, sessions and traces stop once the bundled blipit-java has the same check (blipit-java 0.2.1).

## 0.5.0

- Built on blipit-nuggets, Blipit's own crash-reporting core shared with the Java SDK. The library no longer contains or downloads any third-party crash library.
- Uncaught exceptions on every thread are written to disk synchronously at crash time and sent on the next launch; an offline queue (100 reports, `no_backup` folder) survives process death and network loss.
- Rate limits: a 429 pauses sending for `Retry-After` seconds; server errors retry with backoff; rejected reports are dropped.
- ANR watchdog: main thread blocked for 5 s while on screen (`anr`, `anrTimeoutMs`).
- Native crashes: `libblipit-nuggets.so` signal handler writes signal, thread, registers, raw stack addresses and loaded libraries, sent on the next launch (`nativeCrashes`). 16 KB page aligned.
- Sessions for release health (`sessions`): crashed, abnormal, errored and exited, sent to `/api/v1/sessions`.
- Breadcrumbs for screens, lifecycle, taps, foreground and background, low memory; `setExtra`.
- Session replay recorded by the SDK itself (PixelCopy, on-device masking, MediaCodec H.264) and sent to `/api/v1/replays/{id}/video/{n}`; the crash buffer is kept on disk and sent on the next launch. Mask tag is now `blipit-mask`.
- Activity load spans with `tracesSampleRate`, sent to `/v1/traces`.
- Source changes: `Level` replaces the old level enum, capture calls return the event id as `String?`, `configure`, `dsn` and `apply` are removed.
- README: Google Play Data safety table and Google Play and Huawei AppGallery checklists.
- Stuck screens and flows from 0.4.0 now send through the same offline queue, so they survive a restart and respect rate limits.

## 0.4.0

- Stuck screens and flows: `waitForReady = true` with `Blipit.reportReady()`, and `startFlow`, `flowStep` and `endFlow`. When the app never reaches its first usable screen, or a flow such as sign-in never finishes, Blipit opens a Stuck issue with the last step, the screen and the steps before it. Only time on screen counts; the time limits come from Blipit.
- `startupDeadlineMs` from 0.3.1 is deprecated, now off by default and only switches the check on (any value above 0); the limit comes from Blipit. Apps that relied on the 0.3.1 default set `waitForReady = true`.
- On Android 11 and later, the next start reports when the system ended the app while it was on screen for low memory, too much CPU or memory, or a failed start. No permission is needed and nothing runs in the background.

## 0.3.1

- Stuck on startup: `Blipit.reportReady()` and `startupDeadlineMs`.

## 0.3.0

- Session replay for Android apps, off until `Blipit.setReplayConsent(true)`.
