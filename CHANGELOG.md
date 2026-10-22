## 0.6.0

- Shake to report (`shakeToReport = true`, off by default): shaking the phone opens the reporter on the screen the person is on. Uses the accelerometer only while the app is on screen; no permission. One shake opens one reporter, never while one is already open.
- Screenshot to report (`screenshotToReport = true`, off by default): on Android 14 and later, when the person takes a normal screenshot in the app, a small prompt asks "Report a bug with this screen?" (Report / Not now, gone after 6 seconds). Report opens the reporter with Blipit's own capture of the screen. Adds the normal permission `DETECT_SCREEN_CAPTURE`; the SDK only learns that a screenshot happened and never reads the screenshot, the gallery or storage. Does nothing on Android 13 and older.
- Two-finger press and hold (`twoFingerReport = true`, off by default): two fingers held still for about a second anywhere open the reporter. Touches are only observed, never consumed, so the app's own gestures keep working.
- Report after a crash (`reportAfterCrash = true`, off by default): on the first launch after a crash Blipit caught, a prompt asks "The app closed unexpectedly. Tell us what you were doing?". The report is linked to the crash, so it shows on the crash issue and the crash shows on the report.
- Which screen? (`screenPicker`, on by default because it only changes the reporter UI): when the app opens the reporter from code, for example from a Profile menu, Take a screenshot shows the current screen and up to 3 earlier screens to pick from. The earlier screens are small snapshots kept in memory only, with `blipit-mask` views and password fields blacked out, cleared after 3 minutes in the background or when replay consent is withdrawn; only the chosen one is uploaded. Set `screenPicker = false` to skip it.
- Floating button from the dashboard: the project setting Floating button (Off, Everyone, Testers only) decides whether the Report a bug button shows on its own. The SDK sends the id and email from `setUser` and gets back only yes or no; the tester list stays on the server. `showReportButton()` keeps working as before.
- `Blipit.setScreen(name)`: tells Blipit which screen is showing in single-activity apps (Jetpack Compose, fragments). It adds the navigation breadcrumb, marks the screen in session replay and gives the screen picker one snapshot per named screen.
- For the React Native and Flutter bridges: `setReportHandler`, `attach`, `lastCrashId` and `promptAfterCrash`.

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
