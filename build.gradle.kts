import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.1.0"
    id("maven-publish")
}

group = "io.blipit"
version = "0.1.0"

android {
    namespace = "io.blipit.android"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api("io.sentry:sentry-android:8.58.0")
    testImplementation("junit:junit:4.13.2")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "io.blipit"
            artifactId = "blipit-android"
            version = "0.1.0"
            afterEvaluate {
                from(components["release"])
            }
            pom {
                name.set("Blipit Android")
                description.set("Blipit error monitoring for Android. Know your app broke before your users tell you.")
                url.set("https://docs.blipit.io/mobile")
                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        name.set("Louward Labs")
                        email.set("support@blipit.io")
                    }
                }
                scm {
                    url.set("https://github.com/blipit-io/blipit")
                }
            }
        }
    }
}
