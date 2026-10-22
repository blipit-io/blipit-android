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
