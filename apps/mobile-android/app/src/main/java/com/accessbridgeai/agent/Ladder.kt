package com.accessbridgeai.agent

import com.accessbridgeai.detect.Detection
import com.accessbridgeai.detect.DetectorConfig

object Ladder {
    fun decide(ids: Ids, d: Detection): Decision {
        val top = d.top
        return when {
            top == null ->
                Decision(ids, d.role, Rung.ASK, null, "no candidate")
            top.score >= DetectorConfig.ACT_SCORE && d.margin >= DetectorConfig.ACT_MARGIN ->
                Decision(ids, d.role, Rung.CONFIDENT, top, "score=${"%.2f".format(top.score)} margin=${"%.2f".format(d.margin)}")
            else ->
                Decision(ids, d.role, Rung.ASK, top, "not confident: score=${"%.2f".format(top.score)} margin=${"%.2f".format(d.margin)}")
        }
    }
}