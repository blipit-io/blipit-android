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
implementation("io.blipit:blipit-android:0.1.0")
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

## Performance

Set `tracesSampleRate = 0.2` in `init` and activity loads and requests show up on the Performance page.

Docs: https://docs.blipit.io/mobile
