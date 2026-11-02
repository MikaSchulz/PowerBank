// Pure business logic: only fmodel-domain, no Spring, no Temporal.
plugins {
    kotlin("jvm")
    `java-library`
}

dependencies {
    api(libs.fmodel.domain)
    api(libs.coroutines.core)

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
