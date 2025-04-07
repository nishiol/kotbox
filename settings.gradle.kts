rootProject.name = "kotbox"

pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

include(
    "kotbox-core",
    "kotbox-dialect-provider",
    "kotbox-jackson",
    "kotbox-postgresql",
    "kotbox-spring-boot-starter",
    "kotbox-tests"
)