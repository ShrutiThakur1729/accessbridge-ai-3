package com.accessbridgeai.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.net.Uri
import android.os.Build
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import kotlin.math.max

object LauncherAppResolver {
    private const val TAG = "LauncherAppResolver"
    private const val HIGH_CONFIDENCE = 0.85
    private const val SUGGEST_MIN = 0.65
    private const val PHONETIC_CAP = 0.80
    

    enum class ResolutionStatus {
        MATCH_FOUND,
        AMBIGUOUS_MATCH,
        NO_MATCH
    }

    sealed class LaunchGatingResult {
        data class SIMPLE_LAUNCH(val packageName: String, val appLabel: String, val componentName: ComponentName, val userHandle: UserHandle) : LaunchGatingResult()
        data class AMBIGUOUS_LAUNCH(val candidates: List<MatchCandidate>) : LaunchGatingResult()
        data class SUGGEST_LAUNCH(val candidate: MatchCandidate) : LaunchGatingResult()
        data class COMPLEX_INTENT(
            val packageName: String,
            val appLabel: String,
            val componentName: ComponentName,
            val userHandle: UserHandle,
            val leftoverText: String
        ) : LaunchGatingResult()
        object NO_LOCAL_MATCH : LaunchGatingResult()
    }

    data class ResolutionResult(
        val status: ResolutionStatus,
        val packageName: String? = null,
        val appLabel: String? = null,
        val candidates: List<String> = emptyList(),
        val componentName: ComponentName? = null,
        val userHandle: UserHandle? = null
    )

    fun resolveLauncherApps(context: Context, targetAppName: String, shouldLaunch: Boolean = true): ResolutionResult {
        return resolveApp(context, targetAppName, shouldLaunch)
    }

    fun evaluateLaunchIntent(context: Context, rawTranscript: String, allowSuggestions: Boolean = false): LaunchGatingResult {
        val launchVerbRegex = Regex("^(open|launch|start|go to|show me|run)\\s+", RegexOption.IGNORE_CASE)
        val cleanedTranscript = rawTranscript.replace(launchVerbRegex, "").trim()
        
        if (cleanedTranscript.isEmpty()) return LaunchGatingResult.NO_LOCAL_MATCH

        val words = cleanedTranscript.split(Regex("\\s+"))
        val candidate1 = words.getOrNull(0) ?: ""
        val candidate2 = if (words.size >= 2) "${words[0]} ${words[1]}" else ""

        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
        
        val candidatesList = mutableListOf<MatchCandidate>()

        val profiles = userManager.userProfiles
        for (profile in profiles) {
            val activities = launcherApps.getActivityList(null, profile)
            for (activity in activities) {
                val label = activity.label.toString().lowercase().trim()
                val isManaged = try {
                    if (Build.VERSION.SDK_INT >= 30) {
                        userManager.isManagedProfile
                    } else {
                        profile != android.os.Process.myUserHandle()
                    }
                } catch (e: Exception) { false }
                
                // Check Candidate 1
                val score1 = computeSimilarity(candidate1.lowercase(), label)
                if (score1 >= SUGGEST_MIN) {
                    candidatesList.add(MatchCandidate(score1, activity.componentName.packageName, activity.label.toString(), activity.componentName, profile, isManaged, candidate1))
                }

                // Check Candidate 2
                if (candidate2.isNotEmpty()) {
                    val score2 = computeSimilarity(candidate2.lowercase(), label)
                    if (score2 >= SUGGEST_MIN) {
                        candidatesList.add(MatchCandidate(score2, activity.componentName.packageName, activity.label.toString(), activity.componentName, profile, isManaged, candidate2))
                    }
                }
            }
        }

        if (candidatesList.isNotEmpty()) {
            val ranked = candidatesList
                .sortedWith(
                    compareByDescending<MatchCandidate> { it.score }
                        .thenByDescending { it.matchedQueryPart?.length ?: 0 }   // tie-break: longer matched phrase wins
                )
                .distinctBy { "${it.packageName}_${it.userHandle}" }

            val bestMatch = ranked.first()                            // safe: candidatesList is non-empty

            if (bestMatch.score < HIGH_CONFIDENCE) {
                if (allowSuggestions) {
                    Log.d(TAG, "Suggesting '${bestMatch.label}' (score ${bestMatch.score}) for confirmation")
                    return LaunchGatingResult.SUGGEST_LAUNCH(bestMatch)
                }
                Log.d(TAG, "Best '${bestMatch.label}' scored ${bestMatch.score} < $HIGH_CONFIDENCE; not auto-launching")
                return LaunchGatingResult.NO_LOCAL_MATCH
            }

            val closeCalls = ranked.filter { it.score >= maxOf(HIGH_CONFIDENCE, bestMatch.score - 0.05) }
            if (closeCalls.size > 1) {
                return LaunchGatingResult.AMBIGUOUS_LAUNCH(closeCalls.take(3))
            }
            val leftoverText = cleanedTranscript.substring(bestMatch.matchedQueryPart?.length ?: 0).trim()
                .replace(Regex("^[^a-zA-Z0-9]+|[^a-zA-Z0-9]+$"), "") // Trim punctuation
            
            Log.d(TAG, "Gating Outcome: Best match '${bestMatch.label}' with score ${bestMatch.score}. Leftover Tokens: '$leftoverText'")
            
            return if (leftoverText.isEmpty()) {
                LaunchGatingResult.SIMPLE_LAUNCH(
                    bestMatch.packageName,
                    bestMatch.label,
                    bestMatch.componentName,
                    bestMatch.userHandle
                )
            } else {
                LaunchGatingResult.COMPLEX_INTENT(
                    bestMatch.packageName, bestMatch.label,
                    bestMatch.componentName, bestMatch.userHandle, leftoverText
                )
            }
        }

        Log.d(TAG, "Gating Outcome: NO_LOCAL_MATCH for '$cleanedTranscript'")
        return LaunchGatingResult.NO_LOCAL_MATCH
    }

    fun resolveApp(context: Context, appLabel: String, shouldLaunch: Boolean = true): ResolutionResult {
        // Tier 1: Exact Package Name Check (resolvePackageCache)
        val packageManager = context.packageManager
        val launchIntent = try {
            packageManager.getLaunchIntentForPackage(appLabel)
        } catch (e: Exception) {
            null
        }

        if (launchIntent != null) {
            val pkgInfo = try { packageManager.getPackageInfo(appLabel, 0) } catch (e: Exception) { null }
            val label = pkgInfo?.applicationInfo?.loadLabel(packageManager)?.toString() ?: appLabel
            val result = ResolutionResult(
                ResolutionStatus.MATCH_FOUND,
                appLabel,
                label,
                componentName = launchIntent.component,
                userHandle = android.os.Process.myUserHandle()
            )
            if (shouldLaunch) {
                try {
                    context.startActivity(launchIntent.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                } catch (e: Exception) {
                    Log.e(TAG, "Tier 1 launch failed for $appLabel", e)
                }
            }
            return result
        }

        // Tier 2: Dynamic Fuzzy & Phonetic Match
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager

        val targetLower = appLabel.lowercase().trim()

        // Profile hint detection
        val isWorkRequest = targetLower.contains("work")
        val isPersonalRequest = targetLower.contains("personal") || targetLower.contains("private")
        val cleanTarget = targetLower
            .replace("work", "")
            .replace("personal", "")
            .replace("private", "")
            .trim()

        val candidates = mutableListOf<MatchCandidate>()

        // Scan all profiles: Personal, Work, Cloned, Secure Folder
        val profiles = userManager.userProfiles
        for (profile in profiles) {
            val isManaged = if (Build.VERSION.SDK_INT >= 30) {
                try {
                    val method = userManager.javaClass.getMethod("isManagedProfile", UserHandle::class.java)
                    method.invoke(userManager, profile) as Boolean
                } catch (e: Exception) {
                    profile != android.os.Process.myUserHandle()
                }
            } else {
                profile != android.os.Process.myUserHandle()
            }
            val activities = launcherApps.getActivityList(null, profile)

            for (activity in activities) {
                val label = activity.label.toString()
                val pkg = activity.componentName.packageName
                val labelLower = label.lowercase().trim()

                // Tier 1 logic inside Tier 2 loop for fuzzy matching
                val isExact = label.equals(appLabel, ignoreCase = true) ||
                              pkg.equals(appLabel, ignoreCase = true) ||
                              labelLower == cleanTarget

                var score = if (isExact) 1.0 else computeSimilarity(cleanTarget, labelLower)

                // Boost/Penalize score based on profile hints
                if (isWorkRequest) {
                    if (isManaged) score = minOf(1.0, score + 0.15)
                    else score = maxOf(0.0, score - 0.3)
                } else if (isPersonalRequest) {
                    if (!isManaged) score = minOf(1.0, score + 0.15)
                    else score = maxOf(0.0, score - 0.3)
                }

                candidates.add(MatchCandidate(
                    score = score,
                    packageName = pkg,
                    label = label,
                    componentName = activity.componentName,
                    userHandle = profile,
                    isManaged = isManaged
                ))
            }
        }

        if (candidates.isEmpty()) {
            // Tier 3: Play Store Fallback
            if (shouldLaunch && cleanTarget.isNotEmpty()) {
                launchPlayStoreSearch(context, cleanTarget)
            }
            return ResolutionResult(ResolutionStatus.NO_MATCH)
        }

        // Sort candidates by score
        val sortedCandidates = candidates.sortedByDescending { it.score }
        val bestMatch = sortedCandidates[0]
        
        Log.d(TAG, "Best match for '$appLabel': '${bestMatch.label}' (Managed: ${bestMatch.isManaged}) with score ${bestMatch.score}")

        // Check for multiple exact or very high confidence matches (Ambiguity)
        val highConfidence = sortedCandidates.filter { it.score >= 0.95 }
        if (highConfidence.size > 1 && !isWorkRequest && !isPersonalRequest) {
            // If multiple apps have same label/score and no profile hint was provided
            return ResolutionResult(
                status = ResolutionStatus.AMBIGUOUS_MATCH,
                candidates = highConfidence.take(3).map { "${it.label}${if (it.isManaged) " (Work)" else ""}" }
            )
        }

        val s1Soundex = soundex(cleanTarget)
        val s2Soundex = soundex(bestMatch.label.lowercase().trim())
        val isSoundexFingerprintMatch = s1Soundex == s2Soundex && s1Soundex.isNotEmpty()

        return when {
            bestMatch.score >= 0.85 || isSoundexFingerprintMatch -> {
                val result = ResolutionResult(
                    ResolutionStatus.MATCH_FOUND,
                    bestMatch.packageName,
                    bestMatch.label,
                    componentName = bestMatch.componentName,
                    userHandle = bestMatch.userHandle
                )
                if (shouldLaunch) launch(launcherApps, result)
                result
            }
            bestMatch.score >= 0.65 -> {
                val topCandidates = sortedCandidates
                    .filter { it.score >= 0.65 }
                    .take(3)
                    .map { "${it.label}${if (it.isManaged) " (Work)" else ""}" }
                ResolutionResult(
                    status = ResolutionStatus.AMBIGUOUS_MATCH,
                    appLabel = bestMatch.label,
                    candidates = topCandidates
                )
            }
            else -> {
                // Tier 3: Play Store Fallback for low confidence
                if (shouldLaunch && cleanTarget.isNotEmpty()) {
                    launchPlayStoreSearch(context, cleanTarget)
                }
                ResolutionResult(ResolutionStatus.NO_MATCH)
            }
        }
    }

    private fun launchPlayStoreSearch(context: Context, query: String) {
        try {
            Log.d(TAG, "Tier 3: Launching Play Store search for '$query'")
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://search?q=$query")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Play Store search failed", e)
            try {
                val webIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/search?q=$query")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(webIntent)
            } catch (e2: Exception) {
                Log.e(TAG, "Web Play Store search failed", e2)
            }
        }
    }

    private fun launch(launcherApps: LauncherApps, result: ResolutionResult): Boolean {
        if (result.componentName == null || result.userHandle == null) return false
        return try {
            launcherApps.startMainActivity(result.componentName, result.userHandle, null, null)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start activity for ${result.packageName}", e)
            false
        }
    }

    data class MatchCandidate(
        val score: Double,
        val packageName: String,
        val label: String,
        val componentName: ComponentName,
        val userHandle: UserHandle,
        val isManaged: Boolean,
        val matchedQueryPart: String? = null
    )

    private fun computeSimilarity(a: String, b: String): Double {
        val s1 = a.filterNot { it.isWhitespace() }
        val s2 = b.filterNot { it.isWhitespace() }

        val maxLength = max(s1.length, s2.length)
        val editScore = if (maxLength == 0) 1.0 else 1.0 - (levenshteinDistance(s1, s2).toDouble() / maxLength)

        // Sounding alike can lift a score, but never to auto-launch level
        val soundsAlike = s1.isNotEmpty() && soundex(s1) == soundex(s2)
        val phoneticScore = if (soundsAlike) PHONETIC_CAP else 0.0

        return maxOf(editScore, phoneticScore)
    }

    private fun soundex(s: String): String {
        if (s.isEmpty()) return ""
        val input = s.uppercase().replace(Regex("[^A-Z]"), "")
        if (input.isEmpty()) return ""

        val res = StringBuilder().append(input[0])
        val mapping = mapOf(
            'B' to '1', 'F' to '1', 'P' to '1', 'V' to '1',
            'C' to '2', 'G' to '2', 'J' to '2', 'K' to '2', 'Q' to '2', 'S' to '2', 'X' to '2', 'Z' to '2',
            'D' to '3', 'T' to '3',
            'L' to '4',
            'M' to '5', 'N' to '5',
            'R' to '6'
        )

        var lastDigit = mapping[input[0]] ?: '0'

        for (i in 1 until input.length) {
            val char = input[i]
            if (char == 'H' || char == 'W') continue
            val digit = mapping[char] ?: '0'
            if (digit != '0' && digit != lastDigit) {
                res.append(digit)
            }
            lastDigit = digit
        }

        return res.toString().padEnd(4, '0').substring(0, 4)
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j
        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
            }
        }
        return dp[s1.length][s2.length]
    }
}
