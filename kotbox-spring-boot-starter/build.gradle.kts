import ru.nishiol.kotbox.gradle.kotboxMavenPublish

plugins {
    id("ru.nishiol.kotbox.gradle.common-conventions")
    kotlin("plugin.spring") version libs.versions.kotlin
}

dependencies {
    api(project(":kotbox-core"))
    implementation(platform(libs.spring.boot.dependencies))
    implementation(project(":kotbox-dialect-provider"))
    compileOnly("org.springframework.boot:spring-boot-starter")
    compileOnly("org.springframework:spring-jdbc")
    compileOnly("com.fasterxml.jackson.core:jackson-databind")
    compileOnly(project(":kotbox-jackson"))

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc")
    testImplementation(project(":kotbox-postgresql"))
    testImplementation(project(":kotbox-jackson"))
}

kotboxMavenPublish(artifactId = "kotbox-spring-boot-starter", description = "Kotbox Spring Boot Starter")