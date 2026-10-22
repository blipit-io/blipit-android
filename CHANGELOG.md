## 0.4.0

- Stuck screens and flows: `waitForReady = true` with `Blipit.reportReady()`, and `startFlow`, `flowStep` and `endFlow`. When the app never reaches its first usable screen, or a flow such as sign-in never finishes, Blipit opens a Stuck issue with the last step, the screen and the steps before it. Only time on screen counts; the time limits come from Blipit.
- `startupDeadlineMs` from 0.3.1 is deprecated, now off by default and only switches the check on (any value above 0); the limit comes from Blipit. Apps that relied on the 0.3.1 default set `waitForReady = true`.
- On Android 11 and later, the next start reports when the system ended the app while it was on screen for low memory, too much CPU or memory, or a failed start. No permission is needed and nothing runs in the background.

## 0.3.1

- Stuck on startup: `Blipit.reportReady()` and `startupDeadlineMs`.

## 0.3.0

- Session replay for Android apps, off until `Blipit.setReplayConsent(true)`.
