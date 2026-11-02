plugins {
    // Downloads the matching JDK (toolchain) automatically if it is missing locally.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "temporal-bank"
include("domain", "bank-service")

dependencyResolutionManagement {
    repositories { mavenCentral() }
}
