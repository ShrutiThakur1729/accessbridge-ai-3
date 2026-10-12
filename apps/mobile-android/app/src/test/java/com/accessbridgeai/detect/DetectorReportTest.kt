package com.accessbridgeai.detect
import com.accessbridgeai.screen.RawNode
import com.accessbridgeai.screen.Fixtures
import org.junit.Test
import java.io.File
import com.accessbridgeai.detect.ScreenView
import com.accessbridgeai.detect.ListItemDetector
import com.accessbridgeai.detect.EntryFieldDetector
import com.accessbridgeai.detect.EntryOpenerDetector
import com.accessbridgeai.detect.ScreenDiffer

class DetectorReportTest {
    @Test fun writeReport() {
        val sb = StringBuilder()
        for (name in Fixtures.allNames()) {
            val screen = ScreenView(Fixtures.load(name).screen)
            sb.appendLine("== $name (${screen.nodes.size} nodes)")
            sb.appendLine("  BOTTOM clickables (center y >= 80%):")
            screen.nodes.filter { it.clickable && it.enabled && screen.centerYFraction(it) >= 0.8f && screen.areaFraction(it) < 0.5f }
                .sortedBy { it.left }.take(10).forEach { sb.appendLine("    " + line(screen, it)) }
            val itemIds = ListItemDetector.detect(screen).candidates.filter { it.score >= 0.75f }.map { it.nodeId }.toSet()
            sb.appendLine("  SMALL clickables inside list items:")
            screen.nodes.filter { it.clickable && it.enabled && screen.areaFraction(it) < 0.04f && screen.hasAncestorIn(it, itemIds) }
                .take(8).forEach { sb.appendLine("    " + line(screen, it)) }


            for (d in listOf(EntryFieldDetector.detect(screen), EntryOpenerDetector.detect(screen))) {
                sb.appendLine("  ${d.role}: margin=${"%.2f".format(d.margin)}")
                d.candidates.take(6).forEach { c ->
                    val n = screen.nodes[c.nodeId]
                    sb.appendLine("    id=${c.nodeId} score=${"%.2f".format(c.score)} ${c.reasons} " +
                            "[${n.left},${n.top},${n.right},${n.bottom}] '${screen.label(n).take(30)}'")
                }
            }
            sb.appendLine("  GROUPS (clickable siblings, 3+):")
            screen.clickableGroups().sortedByDescending { it.size }.take(3).forEach { g ->
                val n0 = g.first()
                sb.appendLine("    parent=${n0.parentId} count=${g.size} priced=${g.count { screen.hasCurrency(it) }} " +
                        "first=[${n0.left},${n0.top},${n0.right},${n0.bottom}] '${screen.label(n0).take(30)}'")
            }
            sb.appendLine("  BIG clickables (area >= 4%):")
            screen.nodes.filter { it.clickable && it.enabled && screen.areaFraction(it) >= 0.04f }
                .sortedBy { it.top }.take(12).forEach { n ->
                    sb.appendLine("    id=${n.id} p=${n.parentId} d=${n.depth} " +
                            "area=${"%.1f".format(screen.areaFraction(n) * 100)}% " +
                            "[${n.left},${n.top},${n.right},${n.bottom}] priced=${screen.hasCurrency(n)} " +
                            "'${screen.label(n).take(40)}'")
                }
        }
        val before = ScreenView(Fixtures.load("whatsapp_03_chat_open_Empty").screen)
        val after = ScreenView(Fixtures.load("whatsapp_04_chat_open_typed").screen)
        val diff = ScreenDiffer.diff(before, after)
        sb.appendLine("== DIFF whatsapp_03 -> whatsapp_04")
        sb.appendLine("  APPEARED:");    diff.appeared.forEach { sb.appendLine("    " + line(after, it)) }
        sb.appendLine("  DISAPPEARED:"); diff.disappeared.forEach { sb.appendLine("    " + line(before, it)) }
        val out = File("build/detector-report.txt")
        out.parentFile?.mkdirs()
        out.writeText(sb.toString())
        println("report written to ${out.absolutePath}")
    }
    private fun line(s: ScreenView, n: RawNode) =
        "id=${n.id} p=${n.parentId} ${n.className.substringAfterLast('.')} ${if (n.clickable) "CLICK " else ""}" +
                "[${n.left},${n.top},${n.right},${n.bottom}] '${s.label(n).take(30)}'"

}