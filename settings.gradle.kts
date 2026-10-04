pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/")
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie Releases" }
    }
}
plugins {
    id("dev.kikugie.stonecutter") version "0.9.8"
}
stonecutter {
    create(rootProject) {
        versions("26.1.2")
        vcsVersion = "26.1.2"
    }
}
rootProject.name = "anri-logger"
