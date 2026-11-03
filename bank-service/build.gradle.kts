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
    developmentOnly("org.springframework.boot:spring-boot-docker-compose")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation(libs.temporal.testing)
    testImplementation(libs.archunit)
    testImplementation("io.opentelemetry:opentelemetry-sdk-testing")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
