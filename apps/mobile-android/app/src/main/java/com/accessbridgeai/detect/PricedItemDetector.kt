package com.accessbridgeai.detect

object PricedItemDetector {
    private const val MIN_ITEM_SCORE = DetectorConfig.ACT_SCORE
    fun detect(screen: ScreenView): Detection {
        val items = ListItemDetector.detect(screen).candidates.filter { it.score >= MIN_ITEM_SCORE }
        val priced = items.filter { screen.hasCurrency(screen.nodes[it.nodeId]) }
        val listIsPriced = items.size >= 2 && priced.size * 2 >= items.size
        val out = if (listIsPriced)
            priced.map { it.copy(role = Role.PRICED_ITEM, reasons = it.reasons + "priced_list") }
        else emptyList()
        return Detection(Role.PRICED_ITEM, out)
    }
}