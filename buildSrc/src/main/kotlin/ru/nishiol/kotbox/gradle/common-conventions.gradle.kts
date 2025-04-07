package ru.nishiol.kotbox.gradle

import com.palantir.gradle.gitversion.VersionDetails
import org.gradle.accessors.dm.LibrariesForLibs
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    kotlin("jvm")
    id("com.palantir.git-version")
    id("com.vanniktech.maven.publish")
}

val versionDetails: groovy.lang.Closure<VersionDetails> by extra
val gitVersion = versionDetails().lastTag?.takeIf { it.startsWith("v") }?.substringAfter("v")

group = "ru.nishiol.kotbox"
version = gitVersion ?: "0.0.1-SNAPSHOT"

val libs = the<LibrariesForLibs>()

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(libs.testcontainers.core)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.awaitility)
    testImplementation(libs.assertj)
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        languageVersion = KotlinVersion.KOTLIN_1_9
        apiVersion = KotlinVersion.KOTLIN_1_9
    }
}