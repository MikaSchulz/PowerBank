import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
    alias(libs.plugins.spotless)
    alias(libs.plugins.detekt) apply false
}

// Formatting: ktlint with the IntelliJ IDEA style, so the IDE's "Reformat Code" and the check agree.
// ./gradlew spotlessCheck runs as part of `check` (and so in CI); ./gradlew spotlessApply fixes the files.
val ktlintVersion = libs.versions.ktlint.get()
val ktlintSettings = mapOf(
    "ktlint_code_style" to "intellij_idea",
    "max_line_length" to "140",
    // Left to the author: the layout of class and function signatures (one parameter per line or not, where an
    // expression body starts, e.g. given / whenCommand / thenEvents in the decider tests) and blank lines between
    // when branches. ktlint still checks indentation, wrapping of long lines, imports, spacing and the like.
    "ktlint_standard_class-signature" to "disabled",
    "ktlint_standard_function-signature" to "disabled",
    "ktlint_standard_blank-line-between-when-conditions" to "disabled",
)

spotless {
    kotlinGradle {
        target("*.gradle.kts", "*/*.gradle.kts")
        ktlint(ktlintVersion).editorConfigOverride(ktlintSettings)
    }
}

val javaVersion = providers.gradleProperty("javaVersion").map(String::toInt).get()

subprojects {
    group = "com.example.bank"
    version = "0.1.0-SNAPSHOT"

    // Static analysis: detekt with its default rules plus config/detekt/detekt.yml; runs as part of `check`.
    apply(plugin = "io.gitlab.arturbosch.detekt")
    configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        source.setFrom("src/main/kotlin", "src/test/kotlin", "src/integrationTest/kotlin")
    }
    // detekt's embedded Kotlin compiler only knows JVM targets up to 22; it analyzes source, so the target doesn't
    // change the result. Without this, it takes the toolchain's (25) and fails.
    tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach { jvmTarget = "21" }
    // detekt runs on the Kotlin version it was built with. Spring's dependency management would raise it to the
    // project's version, so this rule is registered after the build script (and with it Spring's rule).
    afterEvaluate {
        configurations.matching { it.name == "detekt" }.configureEach {
            resolutionStrategy.eachDependency {
                if (requested.group == "org.jetbrains.kotlin") useVersion(io.gitlab.arturbosch.detekt.getSupportedKotlinVersion())
            }
        }
    }

    apply(plugin = "com.diffplug.spotless")
    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        kotlin {
            target("src/**/*.kt")
            ktlint(ktlintVersion).editorConfigOverride(ktlintSettings)
        }
    }

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
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
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
