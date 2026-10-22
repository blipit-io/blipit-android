pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

if (file("../sdk-java/settings.gradle.kts").exists()) {
    includeBuild("../sdk-java") {
        dependencySubstitution {
            substitute(module("com.github.blipit-io:blipit-java")).using(project(":"))
        }
    }
}

rootProject.name = "blipit-android"
