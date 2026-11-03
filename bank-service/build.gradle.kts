import java.net.InetSocketAddress
import java.net.Socket

plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation(project(":domain"))

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")          // Jackson 3: REST (Spring Boot 4)
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")  // Jackson 2: Temporal payloads

    implementation(libs.temporal.starter)
    implementation(libs.temporal.kotlin)
    implementation(libs.fmodel.application)

    // Observability: traces, metrics and logs via OpenTelemetry (OTLP)
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    implementation(libs.temporal.opentracing)                    // Temporal's tracing interceptors …
    implementation("io.opentelemetry:opentelemetry-opentracing-shim") // … bridged to OpenTelemetry
    implementation(libs.datasource.micrometer)                   // JDBC spans
    implementation(libs.otel.logback.appender)                   // logs to OTLP

    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation(libs.temporal.testing)
    testImplementation(libs.archunit)
    testImplementation("io.opentelemetry:opentelemetry-sdk-testing")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    // GrafanaProvisioningTest reads the Grafana files and compose.yaml: run the tests again when they change.
    inputs.dir(rootProject.file("observability")).withPropertyName("observability").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.file("compose.yaml")).withPropertyName("compose").withPathSensitivity(PathSensitivity.RELATIVE)
}

// Two kinds of tests:
// - test: no containers (domain, workflow with Temporal's in-memory test environment, ArchUnit, web slices, files).
// - integrationTest: against the local stack from compose.yaml, which you start yourself (docker compose up -d --wait).
//   It uses its own Postgres schema and Temporal namespace, so it doesn't mix with the data of the running app.
@Suppress("UnstableApiUsage")
testing {
    suites {
        register<JvmTestSuite>("integrationTest") {
            dependencies {
                implementation(project())
            }
            targets.all {
                testTask.configure {
                    description = "Runs the integration tests against the local Docker Compose stack."
                    group = LifecycleBasePlugin.VERIFICATION_GROUP
                    shouldRunAfter(tasks.test)
                    doFirst {
                        val down = listOf(5432 to "Postgres", 7233 to "Temporal").filterNot { (port, _) ->
                            runCatching { Socket().use { it.connect(InetSocketAddress("localhost", port), 1000) } }.isSuccess
                        }
                        if (down.isNotEmpty()) {
                            throw GradleException(
                                "${down.joinToString { (port, name) -> "$name (localhost:$port)" }} not reachable. " +
                                    "Start the local stack first: docker compose up -d --wait",
                            )
                        }
                    }
                }
            }
        }
    }
}

configurations["integrationTestImplementation"].extendsFrom(configurations.testImplementation.get())
configurations["integrationTestRuntimeOnly"].extendsFrom(configurations.testRuntimeOnly.get())
