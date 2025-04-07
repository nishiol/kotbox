import ru.nishiol.kotbox.gradle.kotboxMavenPublish

plugins {
    id("ru.nishiol.kotbox.gradle.common-conventions")
}

dependencies {
    api(project(":kotbox-core"))
    compileOnly(project(":kotbox-postgresql"))
}

kotboxMavenPublish(artifactId = "kotbox-dialect-provider", description = "kotbox-dialect-provider")