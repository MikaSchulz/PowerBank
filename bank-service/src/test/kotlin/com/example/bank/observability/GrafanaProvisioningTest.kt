package com.example.bank.observability

import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Grafana dashboard and alert rules in `observability/grafana` are loaded by the LGTM container. Grafana skips
 * broken files with only a log line, so the basic consistency is checked here: valid files, unique IDs, alert rules
 * pointing to panels that exist, and the files mounted by compose.yaml.
 */
class GrafanaProvisioningTest {

    private val root = File("..").canonicalFile
    private val grafana = root.resolve("observability/grafana")

    // YAML is a superset of JSON, so one parser reads both.
    @Suppress("UNCHECKED_CAST")
    private fun load(file: File): Map<String, Any?> = file.reader().use { Yaml().load<Any>(it) as Map<String, Any?> }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.list(key: String) = (this[key] as List<Map<String, Any?>>?).orEmpty()

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.map(key: String) = this[key] as Map<String, Any?>

    private val dashboards = grafana.resolve("dashboards").listFiles { f -> f.extension == "json" }!!.map(::load)
    private val alerting = load(grafana.resolve("provisioning/alerting/powerbank.yaml"))
    private val rules = alerting.list("groups").flatMap { it.list("rules") }

    @Test
    fun `dashboards have unique panel IDs and fit into the 24 column grid`() {
        assertTrue(dashboards.isNotEmpty())
        dashboards.forEach { dashboard ->
            val panels = dashboard.list("panels")
            val ids = panels.map { it["id"] }
            assertEquals(ids.size, ids.toSet().size, "duplicate panel IDs in ${dashboard["uid"]}")
            panels.forEach { panel ->
                val grid = panel.map("gridPos")
                assertTrue((grid["x"] as Int) + (grid["w"] as Int) <= 24, "panel ${panel["title"]} is wider than the grid")
                val datasource = (panel["datasource"] as Map<*, *>?)?.get("uid")
                assertTrue(datasource == null || datasource in setOf("prometheus", "loki", "tempo"), "unknown data source $datasource")
            }
        }
    }

    @Test
    fun `alert rules are unique, have their condition and point to existing panels`() {
        assertTrue(rules.isNotEmpty())
        assertEquals(rules.size, rules.map { it["uid"] }.toSet().size, "duplicate rule UIDs")
        val panelsByDashboard = dashboards.associate { d -> d["uid"] to d.list("panels").map { it["id"].toString() }.toSet() }

        rules.forEach { rule ->
            val title = rule["title"]
            assertTrue(rule.list("data").any { it["refId"] == rule["condition"] }, "$title: condition ${rule["condition"]} has no query")
            assertTrue(rule.map("labels")["severity"] in setOf("critical", "warning"), "$title: severity missing")
            val annotations = rule.map("annotations")
            val dashboard = annotations["__dashboardUid__"] ?: return@forEach
            val panels = panelsByDashboard[dashboard] ?: error("$title: dashboard $dashboard does not exist")
            assertTrue(annotations["__panelId__"].toString() in panels, "$title: panel ${annotations["__panelId__"]} does not exist")
        }
    }

    @Test
    fun `the critical ledger alert watches the ledger gauge`() {
        val ledger = rules.single { it["uid"] == "powerbank-ledger-unbalanced" }
        assertEquals("critical", ledger.map("labels")["severity"])
        assertTrue(ledger.list("data").any { it.map("model")["expr"].toString().contains("bank_ledger_sum_cents") })
    }

    @Test
    fun `compose mounts files that exist`() {
        val volumes = load(root.resolve("compose.yaml")).map("services").let { it["lgtm"] as Map<*, *> }["volumes"] as List<*>
        assertTrue(volumes.isNotEmpty())
        volumes.map { it.toString().substringBefore(':') }.forEach { source ->
            assertTrue(root.resolve(source).exists(), "compose.yaml mounts $source, which does not exist")
        }
    }
}
