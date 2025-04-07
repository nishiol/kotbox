import ru.nishiol.kotbox.gradle.kotboxMavenPublish

plugins {
    id("ru.nishiol.kotbox.gradle.common-conventions")
}

dependencies {
    api(project(":kotbox-core"))
    implementation(libs.jackson)
}

kotboxMavenPublish(artifactId = "kotbox-jackson", description = "kotbox-jackson")