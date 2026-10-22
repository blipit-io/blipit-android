import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.1.0"
    id("maven-publish")
}

group = "io.blipit"
version = "0.6.0"

android {
    namespace = "io.blipit.android"
    compileSdk = 35

    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 21
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            ndkBuild {
                abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            }
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    testOptions {
        targetSdk = 35
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
    api("com.github.blipit-io:blipit-java:v0.2.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "io.blipit"
            artifactId = "blipit-android"
            version = "0.6.0"
            afterEvaluate {
                from(components["release"])
            }
            pom {
                name.set("Blipit Android")
                description.set("Blipit error monitoring for Android, built on blipit-nuggets. Know your app broke before your users tell you.")
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
