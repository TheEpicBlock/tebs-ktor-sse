plugins {
    // Apply the foojay-resolver plugin to allow automatic download of JDKs
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "tebs-ktor-sse"
include("lib")
project(":lib").name = "tebs-ktor-sse"
