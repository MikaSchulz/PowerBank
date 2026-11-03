import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
}

val javaVersion = providers.gradleProperty("javaVersion").map(String::toInt).get()

subprojects {
    group = "com.example.bank"
    version = "0.1.0-SNAPSHOT"

    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(javaVersion)
            compilerOptions {
                freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
            }
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    }
}

// The local stack (compose.yaml) is started and stopped by hand, never by the app or the unit tests.
// These are shortcuts for the docker compose commands.
tasks.register<Exec>("composeUp") {
    group = "docker"
    description = "Starts Postgres, Temporal and Grafana LGTM and waits until they are ready (docker compose up -d --wait)."
    commandLine("docker", "compose", "up", "-d", "--wait")
}

tasks.register<Exec>("composeDown") {
    group = "docker"
    description = "Stops the local stack; the volumes (ledger, workflows) are kept (docker compose down)."
    commandLine("docker", "compose", "down")
}
