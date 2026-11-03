package com.example.bank.architecture

import com.example.bank.workflow.fixture.NonDeterministicWorkflow
import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaConstructorCall
import com.tngtech.archunit.core.domain.JavaMethodCall
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import kotlin.test.assertTrue

/**
 * Architecture rules as tests: the module boundaries and Temporal's determinism rules
 * described in docs/architecture.md and decision 0003.
 */
class ArchitectureTest {

    private val classes = ClassFileImporter()
        .withImportOption(ImportOption.DoNotIncludeTests())
        .importPackages("com.example.bank")

    @Test
    fun `workflow code only uses Temporal and its activity contracts`() = workflowsAreIsolated.check(classes)

    @Test
    fun `workflow code is deterministic`() = workflowsAreDeterministic.check(classes)

    @Test
    fun `the domain stays free of Spring, Temporal and the database`() {
        classes().that().resideInAPackage("com.example.bank.domain..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                "com.example.bank.domain..", "java..", "kotlin..", "kotlinx.coroutines..", "com.fraktalio..", "org.jetbrains.annotations..",
            )
            .because("business logic must be testable without infrastructure")
            .check(classes)
    }

    @Test
    fun `the ledger does not know who calls it`() {
        noClasses().that().resideInAPackage("..bank.ledger..")
            .should().dependOnClassesThat().resideInAnyPackage("..bank.workflow..", "..bank.activities..", "..bank.api..")
            .check(classes)
    }

    @Test
    fun `controllers don't talk SQL`() {
        noClasses().that().resideInAPackage("..bank.api..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc..", "java.sql..")
            .because("all database access goes through the ledger")
            .check(classes)
    }

    @Test
    fun `the rules catch broken workflow code`() {
        val broken = ClassFileImporter().importClasses(NonDeterministicWorkflow::class.java)

        val determinism = assertThrows<AssertionError> { workflowsAreDeterministic.check(broken) }
        listOf("Instant.now", "UUID.randomUUID", "Thread.sleep", "Random.<init>").forEach {
            assertTrue(it in determinism.message.orEmpty(), "expected a violation for $it")
        }
        assertThrows<AssertionError> { workflowsAreIsolated.check(broken) }
    }

    companion object {
        private const val WORKFLOW = "com.example.bank.workflow.."

        val workflowsAreIsolated: ArchRule = noClasses().that().resideInAPackage(WORKFLOW)
            .should().dependOnClassesThat().resideInAnyPackage(
                "com.example.bank.ledger..", "com.example.bank.api..", "com.example.bank.externalbank..",
                "com.example.bank.customer..", "com.fraktalio..", "kotlinx.coroutines..",
                "org.springframework..", "java.sql..", "javax.sql..",
            )
            .because("workflows only orchestrate: all side effects and decisions live in activities")

        private val forbiddenCalls = listOf(
            Instant::class.java to "now", LocalDate::class.java to "now", LocalDateTime::class.java to "now",
            OffsetDateTime::class.java to "now", ZonedDateTime::class.java to "now",
            System::class.java to "currentTimeMillis", System::class.java to "nanoTime",
            UUID::class.java to "randomUUID", Thread::class.java to "sleep", ThreadLocalRandom::class.java to "current",
        )

        val workflowsAreDeterministic: ArchRule = noClasses().that().resideInAPackage(WORKFLOW)
            .should().callMethodWhere(
                DescribedPredicate.describe<JavaMethodCall>("is non-deterministic (clock, random, sleep)") { call ->
                    forbiddenCalls.any { (owner, name) -> call.targetOwner.isEquivalentTo(owner) && call.name == name }
                },
            )
            .orShould().callConstructorWhere(
                DescribedPredicate.describe<JavaConstructorCall>("creates a java.util.Random") { call ->
                    call.targetOwner.isAssignableTo(java.util.Random::class.java)
                },
            )
            .orShould().dependOnClassesThat().belongToAnyOf(kotlin.random.Random::class.java)
            .because("workflow code is replayed: use Workflow.currentTimeMillis(), Workflow.randomUUID(), Workflow.sleep()")
    }
}
