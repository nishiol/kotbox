plugins {
    id("ru.nishiol.kotbox.gradle.common-conventions")
}

dependencies {
    testImplementation(libs.hikari)
    testImplementation(libs.logback)
    testImplementation(libs.jackson)
    testImplementation(project(":kotbox-core"))
    testImplementation(project(":kotbox-dialect-provider"))
    testImplementation(project(":kotbox-jackson"))
    testImplementation(project(":kotbox-postgresql"))
}