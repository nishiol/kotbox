package ru.nishiol.kotbox.gradle

import com.vanniktech.maven.publish.SonatypeHost
import gradle.kotlin.dsl.accessors._2dc3e570af30fc3d3221f03891c6809d.mavenPublishing
import org.gradle.api.Project
import org.gradle.kotlin.dsl.assign

fun Project.kotboxMavenPublish(artifactId: String, description: String) {
    mavenPublishing {
        coordinates(groupId = group.toString(), artifactId = artifactId, version = version.toString())
        publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
        signAllPublications()
        pom {
            name = artifactId
            this.description = description
            url = "https://github.com/nishiol/kotbox"
            licenses {
                license {
                    name = "MIT License"
                    url = "https://github.com/nishiol/kotbox/blob/main/LICENSE"
                }
            }
            developers {
                developer {
                    id = "nishiol"
                    name = "Oleg Shitikov"
                    email = "schitikov.ol@gmail.com"
                }
            }
            scm {
                connection = "scm:git:git://github.com/nishiol/kotbox.git"
                developerConnection = "scm:git:ssh://github.com/nishiol/kotbox.git"
                url = "https://github.com/nishiol/kotbox"
            }
        }
    }
}