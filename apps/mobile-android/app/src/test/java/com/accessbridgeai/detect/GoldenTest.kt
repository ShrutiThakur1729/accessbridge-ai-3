package com.accessbridgeai.detect

import com.accessbridgeai.screen.Fixtures
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.Locale

class GoldenTest {
    private fun snapshot(): String {
        val sb = StringBuilder()
        for (name in Fixtures.allNames()) {
            val s = ScreenView(Fixtures.load(name).screen)
            val all = listOf(EntryFieldDetector.detect(s), EntryOpenerDetector.detect(s), ListItemDetector.detect(s),
                PricedItemDetector.detect(s), NavBarDetector.detect(s), AddDetector.detect(s), CommitDetector.detect(s))
            for (d in all) {
                val confident = d.candidates.filter { it.score >= DetectorConfig.ACT_SCORE }.map { it.nodeId }.sorted()
                val top = d.top?.let { "${it.nodeId}@${"%.2f".format(Locale.US, it.score)}" } ?: "-"
                sb.appendLine("$name ${d.role} top=$top confident=$confident")
            }
        }
        return sb.toString()
    }

    @Test fun detectorsStillBehaveTheWayWeFroze() {
        val file = File("src/test/resources/detector-golden.txt")
        val now = snapshot()
        if (System.getenv("UPDATE_GOLDEN") == "1" || !file.exists()) { file.writeText(now); return }
        val old = file.readText().replace("\r\n", "\n").lines()
        val cur = now.lines()
        val diffs = (0 until maxOf(old.size, cur.size)).filter { old.getOrNull(it) != cur.getOrNull(it) }
            .take(8).joinToString("\n") { "- ${old.getOrNull(it)}\n+ ${cur.getOrNull(it)}" }
        if (diffs.isNotEmpty()) fail("Detector output changed. If intended, refresh the golden file and say why in the commit.\n$diffs")
    }
}