import ru.nishiol.kotbox.gradle.kotboxMavenPublish

plugins {
    id("ru.nishiol.kotbox.gradle.common-conventions")
}

dependencies {
    api(libs.kobject)
    implementation(libs.logging)
}

kotboxMavenPublish(artifactId = "kotbox-core", description = "Kotbox Core")