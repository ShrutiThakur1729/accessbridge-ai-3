package com.accessbridgeai.utils

import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import org.json.JSONObject

data class AgentTask(
    val kind: String?, val raw: String,
    val query: String? = null, val recipient: String? = null,
    val message: String? = null, val destination: String? = null
) {
    companion object {
        fun from(j: JSONObject): AgentTask {
            fun s(k: String): String? = if (j.isNull(k)) null else j.optString(k).trim().ifEmpty { null }
            return AgentTask(s("kind")?.uppercase(), j.optString("task"), s("query"), s("recipient"), s("message"), s("destination"))
        }
        fun legacy(text: String) = AgentTask(null, text)   // gate path: no structure available yet
    }
}

class AgentLoop(
    private val scope: CoroutineScope,
    private val getRoot: () -> AccessibilityNodeInfo?,
    private val speak: (String, (() -> Unit)?) -> Unit,
    private val openMic: () -> Unit,
    private val tapXY: (Int, Int) -> Boolean,
    private val classify: suspend (String, String) -> String,   // YES / NO / OTHER via /classify-reply
    private val biometric: suspend () -> Boolean,
    private val returnToApp: (String) -> Unit
) {
    companion object {
        const val TAG = "AgentLoop"
        const val MAX_STEPS = 12
        const val DRY_RUN = true   // true = do everything EXCEPT the final Send/Book tap
    }

    private var job: Job? = null
    private var replyWaiter: CompletableDeferred<String>? = null
    val isActive: Boolean get() = job?.isActive == true

    fun start(task: AgentTask, pkg: String) {
        cancel()
        job = scope.launch {
            try {
                Log.d(TAG, "start kind=${task.kind} raw='${task.raw}' pkg=$pkg")
                when (task.kind) {
                    "MESSAGE" -> runMessage(task, pkg)
                    "RIDE" -> runRide(task, pkg)
                    else -> runSearch(task.query ?: queryFrom(task.raw), task.raw, pkg)
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { Log.e(TAG, "loop crashed", e); speak("Something went wrong. I stopped.", null) }
        }
    }

    fun cancel() { replyWaiter?.cancel(); replyWaiter = null; job?.cancel() }

    /** Called first by sendUserIntent. true = the loop consumed this utterance. */
    fun onUserReply(text: String): Boolean {
        if (!isActive) return false
        val t = text.lowercase()
        if (t.contains("stop") || t.contains("cancel")) { cancel(); speak("Okay, stopped.", null); return true }
        val w = replyWaiter
        if (w == null) { speak("I'm working on it. Say stop to cancel.", null); return true }
        w.complete(text); return true
    }

    // ================= PLAYBOOK: SEARCH (unchanged logic) =================
    private suspend fun runSearch(query: String, raw: String, pkg: String) {
        if (query.isBlank()) { speak("I opened the app, but I didn't catch what to look for.", null); return }
        val wantsAdd = Regex("\\b(order|buy|add|get|purchase)\\b").containsMatchIn(raw.lowercase())
        speak("Looking for $query.", null)
        var typed = false; var added = false; var failures = 0

        for (step in 1..MAX_STEPS) {
            val screen = awaitScreen(pkg) ?: run { speak("I lost the app, so I'm stopping.", null); return }
            Log.d(TAG, "step=$step typed=$typed added=$added items=${screen.items.size}")
            // CHECK: payment gate before any action
            if (PaymentGate.isPayment(screen.text)) {
                speak("This is the payment step. I'm stopping here. Please check the total and pay yourself.", null); return
            }
            val before = screen.text.hashCode()
            var acted = false

            if (!typed) {
                val edit = screen.items.firstOrNull { it.editable }
                if (edit != null) { Log.d(TAG, "TYPE '$query' into ${edit.id}"); acted = type(edit.node, query, true); typed = acted }
                else {
                    val opener = screen.items.firstOrNull { it.clickable && !it.isAd && isSearchOpener(it) }
                        ?: run { speak("I can't find a search box on this screen.", null); return }
                    Log.d(TAG, "TAP search opener '${opener.label}' id=${opener.id}"); acted = tap(opener.node)
                }
            } else if (!added) {
                val results = screen.items.filter { it.clickable && !it.isAd && it.label.length >= 30 }.take(4)
                if (results.isEmpty()) { delay(1000); continue }
                val chosen = chooseOne(results, "I found ${results.size} options.") ?: return
                val live = fresh(chosen)
                val addBtn = if (wantsAdd) findAddButton(live.node) else null
                if (addBtn != null) { Log.d(TAG, "TAP add on '${live.label}'"); acted = tap(addBtn); added = acted; speak("Added.", null) }
                else { Log.d(TAG, "TAP result '${live.label}'"); tap(live.node); speak("Opening it.", null); return }
            } else {
                val cart = screen.items.firstOrNull { it.clickable && !it.isAd && isCartOpener(it) }
                    ?: run { speak("I added it, but couldn't find the cart. Please open it yourself.", null); return }
                Log.d(TAG, "TAP cart '${cart.label}'"); acted = tap(cart.node)
            }
            if (!acted || !waitForChange(before)) failures++ else failures = 0
            if (failures >= 2) { speak("That didn't work, so I'm stopping.", null); return }
        }
        speak("That's taking too many steps. I'm stopping.", null)
    }

    // ================= PLAYBOOK: RIDE =================
    private suspend fun runRide(task: AgentTask, pkg: String) {
        val dest = task.destination ?: run { speak("I didn't catch where you want to go.", null); return }
        speak("Finding rides to $dest.", null)

        // 1. reach the destination text box (skip the pickup box)
        var edit: ScreenItem? = null
        for (attempt in 1..3) {
            val s = awaitScreen(pkg) ?: run { speak("I lost the app, so I'm stopping.", null); return }
            edit = s.items.firstOrNull { it.editable && !isPickup(it) }
            if (edit != null) break
            val opener = s.items.filter { it.clickable && !it.isAd && isDestOpener(it) }.minByOrNull { it.area }
                ?: run { speak("I can't find where to enter the destination.", null); return }
            Log.d(TAG, "TAP destination opener '${opener.label}' id=${opener.id}")
            tap(opener.node)
            edit = waitFor(pkg, 4000) { sc -> sc.items.any { it.editable && !isPickup(it) } }
                ?.items?.firstOrNull { it.editable && !isPickup(it) }
            if (edit != null) break
        }
        if (edit == null) { speak("I couldn't open the destination box.", null); return }
        Log.d(TAG, "TYPE destination '$dest' into ${edit.id}")
        type(edit.node, dest, false)

        // 2. suggestions -> user picks
        val words = dest.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 3 }
        val sug = waitFor(pkg, 6000) { sc -> sc.items.any { isSuggestion(it, words) } }
            ?: run { speak("I couldn't find places matching $dest.", null); return }
        val places = sug.items.filter { isSuggestion(it, words) }.distinctBy { it.label }.take(4)
        val place = chooseOne(places, "I found these places.") ?: return
        Log.d(TAG, "TAP place '${place.label}'"); tap(fresh(place).node)

        // 3. ride options -> user picks
        val rides = waitFor(pkg, 12000) { sc -> sc.items.any { isRide(it) } }
            ?: run { speak("I couldn't see any ride options.", null); return }
        val cards = rides.items.filter { isRide(it) }.distinctBy { it.label }.take(5)
        val card = chooseOne(cards, "Here are the rides.") ?: return
        Log.d(TAG, "TAP ride '${card.label}'"); tap(fresh(card).node)
        delay(1200)

        // 4. COMMIT GATE: spoken yes -> biometric -> tap
        val s2 = awaitScreen(pkg) ?: run { speak("I lost the app, so I'm stopping.", null); return }
        if (findCommit(s2) == null) { speak("I picked it, but I can't find the book button. Please tap it yourself.", null); return }
        val ok = confirm("Book ${spoken(card.label)} to $dest. Say yes to confirm.")
        if (ok == null) { speak("I didn't get a clear answer. I won't book it.", null); return }
        if (!ok) { speak("Okay, I won't book it.", null); return }
        if (!biometric()) { speak("The fingerprint check failed. I won't book it.", null); return }
        returnToApp(pkg); delay(1500)
        val btn = awaitScreen(pkg)?.let { findCommit(it) }
            ?: run { speak("I lost the book button. Please finish it yourself.", null); return }
        if (DRY_RUN) { Log.d(TAG, "DRY RUN would TAP '${btn.label}' id=${btn.id}"); speak("Dry run. I would tap ${btn.label} now.", null) }
        else { Log.d(TAG, "COMMIT TAP '${btn.label}'"); tap(btn.node); speak("Booking requested. Please stay on the screen.", null) }
    }

    // ================= PLAYBOOK: MESSAGE =================
    private suspend fun runMessage(task: AgentTask, pkg: String) {
        val who = task.recipient; val text = task.message
        if (who == null || text == null) { speak("I need a name and a message.", null); return }
        speak("Messaging $who.", null)
        var s = awaitScreen(pkg) ?: run { speak("I lost the app, so I'm stopping.", null); return }

        // 1. get to a search box and type ONLY the name
        if (s.items.none { it.editable }) {
            val opener = s.items.firstOrNull { it.clickable && !it.isAd && it.label.contains("search", true) }
                ?: run { speak("I can't find a search box.", null); return }
            Log.d(TAG, "TAP search opener '${opener.label}'"); tap(opener.node)
            s = waitFor(pkg, 4000) { sc -> sc.items.any { it.editable } }
                ?: run { speak("The search box didn't open.", null); return }
        }
        type(s.items.first { it.editable }.node, who, false)

        // 2. find the contact row (group by name, tap the largest node = the row, not the photo)
        val whoLow = who.lowercase()
        val whoWords = whoLow.split(Regex("[^a-z0-9]+")).filter { it.length >= 3 }
        fun loose(i: ScreenItem) = i.clickable && !i.editable && !i.isAd && whoWords.any { i.label.lowercase().contains(it) }
        val res = waitFor(pkg, 6000) { sc -> sc.items.any { loose(it) } }
            ?: run { speak("I couldn't find anyone called $who.", null); return }
        val matches = res.items.filter { loose(it) }
        val strict = matches.filter { it.label.lowercase().startsWith(whoLow) }
        val rows = (strict.ifEmpty { matches })
            .groupBy { nameOf(it.label).lowercase() }
            .map { e -> e.value.maxByOrNull { it.area }!! }
        val contact = chooseOne(rows, "I found these contacts.", say = { nameOf(it.label) }) ?: return
        val name = nameOf(contact.label)
        Log.d(TAG, "TAP contact '$name'"); tap(fresh(contact).node)

        // 3. type the message in the chat box (NOT submitted)
        val chat = waitFor(pkg, 6000) { sc -> sc.items.any { isMessageBox(it) } }
            ?: run { speak("The chat didn't open.", null); return }
        type(chat.items.first { isMessageBox(it) }.node, text, false)

        // 4. COMMIT GATE: read back, spoken yes, then tap Send
        val ok = confirm("Send to $name: $text. Say yes to send.")
        if (ok == null) { speak("I didn't get a clear answer. I won't send it.", null); return }
        if (!ok) { speak("Okay, I won't send it.", null); return }
        val send = awaitScreen(pkg)?.items?.firstOrNull { it.clickable && Regex("^send\\b", RegexOption.IGNORE_CASE).containsMatchIn(it.label) }
            ?: run { speak("I typed the message but can't find Send. Please tap it yourself.", null); return }
        if (DRY_RUN) { Log.d(TAG, "DRY RUN would TAP '${send.label}' id=${send.id}"); speak("Dry run. I would tap Send now.", null) }
        else { tap(send.node); speak("Sent.", null) }
    }

    // ================= shared helpers =================
    private suspend fun ask(question: String): String? {
        val w = CompletableDeferred<String>(); replyWaiter = w
        speak(question) { openMic() }          // mic opens only after the question is fully spoken
        val r = withTimeoutOrNull(45_000) { w.await() }
        replyWaiter = null; return r
    }

    /** yes/no via the AI classifier (no hardcoded word list). null = unclear twice. */
    private suspend fun confirm(question: String): Boolean? {
        repeat(2) {
            val r = ask(question) ?: return null
            when (classify(question, r)) { "YES" -> return true; "NO" -> return false }
        }
        return null
    }

    private suspend fun chooseOne(options: List<ScreenItem>, intro: String, say: (ScreenItem) -> String = { spoken(it.label) }): ScreenItem? {
        if (options.isEmpty()) { speak("I couldn't find any matching options.", null); return null }
        if (options.size == 1) {
            val yes = confirm("$intro ${say(options[0])}. Is that right?")
            if (yes == true) return options[0]
            speak(if (yes == false) "Okay, stopping." else "I didn't get a clear answer. Stopping.", null); return null
        }
        var retry = ""
        repeat(2) {
            val q = retry + intro + " " + options.mapIndexed { i, x -> "${i + 1}. ${say(x)}" }.joinToString(". ") + ". Which one?"
            val reply = ask(q) ?: run { speak("I didn't hear an answer. Stopping.", null); return null }
            pickIndex(reply, options, say)?.let { return options[it] }
            retry = "Sorry, I couldn't tell which one. "
        }
        speak("I still couldn't tell. Stopping.", null); return null
    }

    private suspend fun waitFor(pkg: String, timeoutMs: Long, pred: (Screen) -> Boolean): Screen? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val root = getRoot()
            if (root != null && root.packageName?.toString() == pkg) {
                val s = ScreenReader.read(root)
                if (s.items.isNotEmpty() && pred(s)) return s
            }
            delay(400)
        }
        Log.w(TAG, "waitFor gave up: pkg=$pkg"); return null
    }

    private suspend fun awaitScreen(pkg: String): Screen? = waitFor(pkg, 8000) { true }

    private suspend fun waitForChange(before: Int): Boolean {
        repeat(10) {
            delay(400)
            val root = getRoot() ?: return@repeat
            if (ScreenReader.read(root).text.hashCode() != before) return true
        }
        return false
    }

    /** Nodes go stale while the user thinks; re-find the same item on the live screen. */
    private fun fresh(item: ScreenItem): ScreenItem {
        val root = getRoot() ?: return item
        return ScreenReader.read(root).items.firstOrNull { it.label == item.label && it.id == item.id } ?: item
    }

    private suspend fun type(node: AccessibilityNodeInfo, text: String, submit: Boolean): Boolean {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        delay(500)
        if (submit && Build.VERSION.SDK_INT >= 30) node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        delay(1200)
        return ok
    }

    private fun tap(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node; var hops = 0
        while (n != null && hops < 4) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            n = n.parent; hops++
        }
        val r = Rect(); node.getBoundsInScreen(r); return tapXY(r.centerX(), r.centerY())
    }

    private fun findAddButton(card: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (i in 0 until card.childCount) {
            val c = card.getChild(i) ?: continue
            if (c.isClickable && Regex("\\badd\\b", RegexOption.IGNORE_CASE).containsMatchIn(ScreenReader.labelOf(c))) return c
            findAddButton(c)?.let { return it }
        }
        return null
    }

    // ---- screen-type rules (generic heuristics, documented hardcoding) ----
    private fun isSearchOpener(i: ScreenItem) = i.label.contains("search", true) || i.id?.contains("search", true) == true
    private fun isDestOpener(i: ScreenItem) = Regex("where to|where are you going|destination|search", RegexOption.IGNORE_CASE).containsMatchIn(i.label)
    private fun isPickup(i: ScreenItem) = Regex("pick ?up", RegexOption.IGNORE_CASE).containsMatchIn(i.label + " " + (i.id ?: ""))
    private fun isSuggestion(i: ScreenItem, words: List<String>) =
        i.clickable && !i.editable && !i.isAd && words.any { i.label.lowercase().contains(it) }
    private fun isRide(i: ScreenItem) = i.clickable && !i.isAd && i.label.contains("₹") &&
        Regex("fare|price", RegexOption.IGNORE_CASE).containsMatchIn(i.label)
    private fun isMessageBox(i: ScreenItem) = i.editable && (i.label.equals("message", true) || i.id?.endsWith("/entry") == true)
    private fun findCommit(s: Screen): ScreenItem? =
        s.items.firstOrNull { it.clickable && (it.id?.contains("request_button") == true || it.id?.contains("btn_book") == true) }
            ?: s.items.firstOrNull { it.clickable && !isRide(it) && Regex("^(book|request|confirm)\\b", RegexOption.IGNORE_CASE).containsMatchIn(it.label) }
    private fun isCartOpener(i: ScreenItem) =
        (i.id?.contains("cart", true) == true || i.label.startsWith("cart", true)) &&
            !Regex("\\badd\\b|remove", RegexOption.IGNORE_CASE).containsMatchIn(i.label)

    private fun nameOf(label: String) = label.substringBefore(",").removeSuffix(" picture").trim()
    private fun spoken(label: String) = label.substringBefore(" – ").replace("|", " ").removePrefix("selected,").take(100)

    private fun pickIndex(reply: String, r: List<ScreenItem>, say: (ScreenItem) -> String): Int? {
        val words = reply.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        val ord = mapOf("first" to 0, "1" to 0, "second" to 1, "2" to 1, "two" to 1,
            "third" to 2, "3" to 2, "three" to 2, "fourth" to 3, "4" to 3, "four" to 3, "fifth" to 4, "5" to 4, "last" to r.size - 1)
        words.firstNotNullOfOrNull { ord[it] }?.let { if (it in r.indices) return it }
        val stop = setOf("the", "one", "please", "option", "number", "that", "this")
        val scores = r.map { x -> words.count { w -> w.length >= 3 && w !in stop && (say(x) + " " + x.label).lowercase().contains(w) } }
        val best = scores.max()
        return if (best > 0 && scores.count { it == best } == 1) scores.indexOf(best) else null
    }

    // TEMPORARY fallback for the gate path (no structured fields yet)
    private fun queryFrom(task: String) = task
        .replace(Regex("^(please\\s+)?(order|buy|get|search for|search|find|play|watch|show me|add)\\s+((some|a|an|the)\\s+)?", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s+(for me|please)$", RegexOption.IGNORE_CASE), "").trim()
}