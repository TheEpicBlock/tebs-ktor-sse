plugins {
    // Apply the org.jetbrains.kotlin.jvm Plugin to add support for Kotlin.
    alias(libs.plugins.jvm)

    // Apply the java-library plugin for API and implementation separation.
    `java-library`
    `maven-publish`
}

group = "nl.theepicblock"
version = "0.1.0"

repositories {
    // Use Maven Central for resolving dependencies.
    mavenCentral()
}

dependencies {
    // Use the Kotlin JUnit 5 integration.
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    // Use the JUnit 5 integration.
    testImplementation(libs.junit.jupiter.engine)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    api(libs.ktor.core)
    testRuntimeOnly(libs.ktor.cio)
}

// Apply a specific Java toolchain to ease working on different environments.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.named<Test>("test") {
    // Use JUnit Platform for unit tests.
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>(rootProject.name) {
            from(components["java"])

            pom {
                url = "https://github.com/TheEpicBlock/tebs-ktor-sse"

                licenses {
                    license {
                        name = "AGPL-3.0-or-later"

                    }
                }

                scm {
                    connection = "scm:git:git://github.com/TheEpicBlock/tebs-ktor-sse.git"
                    developerConnection = "scm:git:ssh://github.com:TheEpicBlock/tebs-ktor-sse.git"
                    url = "https://github.com/TheEpicBlock/tebs-ktor-sse"
                }

                developers {
                    developer {
                        name = "TheEpicBlock"
                        url = "https://theepicblock.nl"
                    }
                }

                suppressAllPomMetadataWarnings()
                withXml {
                    asElement().getElementsByTagName("dependency").forEach { dep ->
                        var groupId: String? = null;
                        var artifactId: String? = null;
                        dep.childNodes.forEach {
                            when (it.nodeName) {
                                "groupId" -> groupId = it.textContent
                                "artifactId" -> artifactId = it.textContent
                            }
                        }
                        val v = configurations["runtimeClasspath"].resolvedConfiguration.resolvedArtifacts.find {
                            val mvi = it.moduleVersion.id
                            return@find mvi.group == groupId && mvi.name.contains(artifactId.toString())
                        }
                        if (v != null) {
                            dep.childNodes.forEach {
                                if (it.nodeName == "version") {
                                    it.textContent = v.moduleVersion.id.version
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    repositories {
        maven {
            name = "teb"
            credentials(PasswordCredentials::class)
            url = uri("https://maven.theepicblock.nl")
        }
    }
}

inline fun org.w3c.dom.NodeList.forEach(consumer: (org.w3c.dom.Node) -> Unit) {
    for (i in 0..this.length - 1) {
        consumer(this.item(i))
    }
}