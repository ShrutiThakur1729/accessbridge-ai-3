package com.accessbridgeai.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import java.util.*
import kotlin.math.max
import kotlin.math.min

object AppResolver {

    data class AppInfo(
        val label: String,
        val packageName: String
    )

    enum class Confidence {
        HIGH_CONFIDENCE,
        AMBIGUOUS,
        NOT_INSTALLED,
        MULTI_STEP_INTENT
    }

    data class AppResolutionResult(
        val confidence: Confidence,
        val bestMatch: AppInfo? = null,
        val candidates: List<AppInfo> = emptyList(),
        val score: Double = 0.0,
        val originalPrompt: String? = null
    )

    /**
     * Queries PackageManager dynamically for all installed launchable apps.
     */
    fun getInstalledApps(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(intent, 0)
        return resolveInfos.map {
            AppInfo(
                label = it.loadLabel(pm).toString(),
                packageName = it.activityInfo.packageName
            )
        }
    }

    // Comprehensive multilingual launch & action verbs
    private val ACTION_VERBS = setOf(
        // English
        "open", "launch", "start", "go to", "show me", "run", "play", "watch", "search", "find",
        // Hindi / Hinglish
        "kholo", "khol", "chalao", "chala", "lagao", "laga", "shuru karo", "dikhao", "dikhayein",
        "open karo", "play karo", "chalu karo", "dekho", "bajao",
        // Marathi
        "ughada", "chalu kara", "dakhva", "lav", "pahawa"
    )

    // Conjunctions / Connectors that indicate compound sub-tasks
    private val COMPOUND_CONNECTORS = setOf(
        "aur", "and", "then", "phir", "aani", "par", "pe", "mein", "in", "on", "for"
    )

    /**
     * Resolves an app from spoken input using phonetic matching and string similarity,
     * accounting for multilingual commands and multi-step compound intents.
     */
    fun resolveApp(context: Context, spokenInput: String): AppResolutionResult {
        val installedApps = getInstalledApps(context)
        val cleanedInput = spokenInput.lowercase(Locale.ROOT).trim()
        
        if (cleanedInput.isEmpty()) {
            val result = AppResolutionResult(Confidence.NOT_INSTALLED)
            android.util.Log.d("AppResolver", "Resolution result: $result")
            return result
        }

        // Tokenize cleaned input
        val tokens = cleanedInput.split(Regex("\\s+")).filter { it.isNotBlank() }

        // Check if the input is a compound command
        var isCompound = false
        var verbCount = 0
        for (token in tokens) {
            if (COMPOUND_CONNECTORS.contains(token)) {
                isCompound = true
            }
            if (ACTION_VERBS.contains(token)) {
                verbCount++
            }
        }
        if (verbCount >= 2) {
            isCompound = true
        }

        // Extract target app query by stripping verbs
        val inputTokensWithoutVerbs = tokens.filter { !ACTION_VERBS.contains(it) }
        val inputWithoutVerb = inputTokensWithoutVerbs.joinToString(" ")

        val matches = mutableListOf<Pair<AppInfo, Double>>()

        for (app in installedApps) {
            val label = app.label.lowercase(Locale.ROOT)
            val labelTokens = label.split(Regex("\\s+")).filter { it.isNotBlank() }
            
            // 1. Exact string match
            if (inputWithoutVerb == label || cleanedInput == label) {
                matches.add(app to 1.0)
                continue
            }

            // 2. Exact single-word token match against app label (e.g. "youtube" matches "youtube")
            val tokenMatch = labelTokens.any { labelTok ->
                inputTokensWithoutVerbs.contains(labelTok) || labelTok == inputWithoutVerb
            }
            val exactTokenScore = if (tokenMatch) 0.95 else 0.0

            // 3. Jaro-Winkler Similarity
            val jwScore = jaroWinkler(inputWithoutVerb, label)
            
            // 4. Phonetic Similarity (Double Metaphone)
            // SAFETY GUARD: DoubleMetaphone only applied if JW score is >= 0.60 to prevent collisions
            // (e.g. "youtube kholo" -> "ATPK" matching "adobe acrobat" -> "ATPK")
            val phoneticScore = if (jwScore >= 0.60) {
                val inputPhonetic = doubleMetaphone(inputWithoutVerb)
                val labelPhonetic = doubleMetaphone(label)
                if (inputPhonetic.first == labelPhonetic.first && inputPhonetic.first.isNotEmpty()) 0.90 else 0.0
            } else 0.0

            // 5. Substring match boost
            val substringScore = if (inputWithoutVerb.isNotEmpty() && (inputWithoutVerb.contains(label) || label.contains(inputWithoutVerb))) {
                0.80
            } else 0.0
            
            val finalScore = maxOf(exactTokenScore, jwScore, phoneticScore, substringScore)
            matches.add(app to finalScore)
        }

        val sortedMatches = matches.filter { it.second >= 0.60 }.sortedByDescending { it.second }

        if (sortedMatches.isEmpty()) {
            val result = AppResolutionResult(Confidence.NOT_INSTALLED, originalPrompt = spokenInput)
            android.util.Log.d("AppResolver", "Resolution result: $result")
            return result
        }

        val best = sortedMatches[0]
        
        // Check for leftover non-verb, non-label tokens
        val bestLabelLower = best.first.label.lowercase(Locale.ROOT)
        val bestLabelTokens = bestLabelLower.split(Regex("\\s+"))
        val leftoverTokens = inputTokensWithoutVerbs.filter { !bestLabelTokens.contains(it) }
        val hasLeftoverTokens = leftoverTokens.isNotEmpty()

        if (isCompound || hasLeftoverTokens) {
            isCompound = true
        }

        val confidence = when {
            isCompound && best.second >= 0.50 -> Confidence.MULTI_STEP_INTENT
            best.second >= 0.85 -> Confidence.HIGH_CONFIDENCE
            best.second >= 0.60 -> Confidence.AMBIGUOUS
            else -> Confidence.NOT_INSTALLED
        }

        val result = AppResolutionResult(
            confidence = confidence,
            bestMatch = best.first,
            candidates = sortedMatches.map { it.first }.take(3),
            score = best.second,
            originalPrompt = spokenInput
        )
        Log.d("AppResolver", "Resolution result for '$spokenInput': best='${best.first.label}' score=${best.second} confidence=$confidence")
        return result
    }

    /**
     * Jaro-Winkler Similarity Algorithm.
     */
    fun jaroWinkler(s1: String, s2: String): Double {
        val jaro = jaro(s1, s2)
        if (jaro < 0.7) return jaro
        
        var prefixLength = 0
        val maxPrefix = 4
        for (i in 0 until min(min(s1.length, s2.length), maxPrefix)) {
            if (s1[i] == s2[i]) prefixLength++
            else break
        }
        
        return jaro + (prefixLength * 0.1 * (1.0 - jaro))
    }

    private fun jaro(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0
        val len1 = s1.length
        val len2 = s2.length
        if (len1 == 0 || len2 == 0) return 0.0

        val matchWindow = max(0, max(len1, len2) / 2 - 1)
        val matches1 = BooleanArray(len1)
        val matches2 = BooleanArray(len2)
        
        var matches = 0
        for (i in 0 until len1) {
            val start = max(0, i - matchWindow)
            val end = min(i + matchWindow + 1, len2)
            for (j in start until end) {
                if (!matches2[j] && s1[i] == s2[j]) {
                    matches1[i] = true
                    matches2[j] = true
                    matches++
                    break
                }
            }
        }
        
        if (matches == 0) return 0.0
        
        var transpositions = 0.0
        var k = 0
        for (i in 0 until len1) {
            if (matches1[i]) {
                while (!matches2[k]) k++
                if (s1[i] != s2[k]) transpositions++
                k++
            }
        }
        
        val m = matches.toDouble()
        return (m / len1 + m / len2 + (m - transpositions / 2.0) / m) / 3.0
    }

    /**
     * Simplified Double Metaphone implementation.
     * Returns primary and optional secondary phonetic encodings.
     */
    fun doubleMetaphone(input: String): Pair<String, String?> {
        val s = input.uppercase(Locale.ROOT).replace(Regex("[^A-Z]"), "")
        if (s.isEmpty()) return "" to null
        
        val primary = StringBuilder()
        val secondary = StringBuilder()
        var current = 0
        
        while (primary.length < 4 && current < s.length) {
            val char = s[current]
            when (char) {
                'A', 'E', 'I', 'O', 'U', 'Y' -> {
                    if (current == 0) {
                        primary.append('A')
                        secondary.append('A')
                    }
                    current++
                }
                'B' -> {
                    primary.append('P')
                    secondary.append('P')
                    current = if (current + 1 < s.length && s[current + 1] == 'B') current + 2 else current + 1
                }
                'C' -> {
                    primary.append('K')
                    secondary.append('K')
                    current++
                }
                'D' -> {
                    primary.append('T')
                    secondary.append('T')
                    current++
                }
                'F', 'V' -> {
                    primary.append('F')
                    secondary.append('F')
                    current++
                }
                'G' -> {
                    primary.append('K')
                    secondary.append('K')
                    current++
                }
                'H' -> {
                    current++
                }
                'J' -> {
                    primary.append('J')
                    secondary.append('J')
                    current++
                }
                'K', 'Q' -> {
                    primary.append('K')
                    secondary.append('K')
                    current++
                }
                'L' -> {
                    primary.append('L')
                    secondary.append('L')
                    current++
                }
                'M', 'N' -> {
                    primary.append('N')
                    secondary.append('N')
                    current++
                }
                'P' -> {
                    primary.append('P')
                    secondary.append('P')
                    current++
                }
                'R' -> {
                    primary.append('R')
                    secondary.append('R')
                    current++
                }
                'S' -> {
                    primary.append('S')
                    secondary.append('S')
                    current++
                }
                'T' -> {
                    primary.append('T')
                    secondary.append('T')
                    current++
                }
                'X' -> {
                    primary.append('S')
                    secondary.append('S')
                    current++
                }
                'W' -> {
                    current++
                }
                'Z' -> {
                    primary.append('S')
                    secondary.append('S')
                    current++
                }
                else -> current++
            }
        }
        
        val p = primary.toString().padEnd(4, ' ').substring(0, 4).trim()
        val sec = if (secondary.toString() != primary.toString()) 
            secondary.toString().padEnd(4, ' ').substring(0, 4).trim() 
            else null
            
        return p to sec
    }
}
