package com.accessbridgeai.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Build
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import kotlin.math.max

object LauncherAppResolver {
    private const val TAG = "LauncherAppResolver"

    enum class ResolutionStatus {
        MATCH_FOUND,
        AMBIGUOUS_MATCH,
        NO_MATCH
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

    fun resolveApp(context: Context, appLabel: String, shouldLaunch: Boolean = true): ResolutionResult {
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

        // Scan all profiles: Personal, Work, Cloned
        val profiles = userManager.userProfiles
        for (profile in profiles) {
            val isManaged = if (Build.VERSION.SDK_INT >= 30) {
                // UserManager.isManagedProfile(UserHandle) was added in API 30
                // We use a reflective check to avoid compile-time issues if the IDE's SDK version is confused,
                // or just rely on the fact that isManagedProfile() without args is for the calling user.
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
                
                // Tier 1: Exact Match (Case-Insensitive)
                val isExact = label.equals(appLabel, ignoreCase = true) || 
                              pkg.equals(appLabel, ignoreCase = true) ||
                              labelLower == cleanTarget

                var score = if (isExact) 1.0 else computeSimilarity(cleanTarget, labelLower)
                
                // Boost/Penalize score based on profile hints to ensure deterministic resolution
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

        if (candidates.isEmpty()) return ResolutionResult(ResolutionStatus.NO_MATCH)

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
                ResolutionResult(ResolutionStatus.NO_MATCH)
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

    private data class MatchCandidate(
        val score: Double,
        val packageName: String,
        val label: String,
        val componentName: ComponentName,
        val userHandle: UserHandle,
        val isManaged: Boolean
    )

    private fun computeSimilarity(s1: String, s2: String): Double {
        // Levenshtein Distance Ratio (0.0 to 1.0)
        val levenshtein = levenshteinDistance(s1, s2)
        val maxLength = max(s1.length, s2.length)
        val editScore = if (maxLength == 0) 1.0 else 1.0 - (levenshtein.toDouble() / maxLength)
        
        // Phonetic Matching
        val s1Soundex = soundex(s1)
        val s2Soundex = soundex(s2)
        
        // Exact Soundex match gives 1.0
        val soundexMatch = if (s1Soundex == s2Soundex && s1Soundex.isNotEmpty()) 1.0 else 0.0
        
        // Double Metaphone logic
        val dm1 = doubleMetaphone(s1)
        val dm2 = doubleMetaphone(s2)
        val metaphoneScore = when {
            dm1.first == dm2.first -> 0.95
            dm1.second == dm2.first && dm1.second != null -> 0.85
            dm1.first == dm2.second && dm2.second != null -> 0.85
            else -> 0.0
        }
        
        // Combined Phonetic Score
        val phoneticMatch = maxOf(soundexMatch, metaphoneScore)
        
        // Combined Score = max(Levenshtein Ratio, Phonetic Match Score)
        return maxOf(editScore, phoneticMatch)
    }

    private fun doubleMetaphone(s: String): Pair<String, String?> {
        if (s.isEmpty()) return "" to null
        val input = s.uppercase().replace(Regex("[^A-Z]"), "")
        if (input.isEmpty()) return "" to null

        val primary = StringBuilder()
        val secondary = StringBuilder()
        var i = 0

        while (i < input.length && (primary.length < 4 || secondary.length < 4)) {
            val char = input[i]
            when (char) {
                'A', 'E', 'I', 'O', 'U', 'Y' -> {
                    if (i == 0) {
                        // Map all initial vowels and Y to a common code for phonetic flexibility (e.g., YouTube/UTube)
                        primary.append('A')
                        secondary.append('A')
                    }
                }
                'B' -> { primary.append('P'); secondary.append('P') }
                'C' -> {
                    if (i + 1 < input.length && input[i + 1] == 'H') {
                        primary.append('X'); secondary.append('X')
                        i++
                    } else if (i + 1 < input.length && (input[i + 1] == 'I' || input[i + 1] == 'E' || input[i + 1] == 'Y')) {
                        primary.append('S'); secondary.append('S')
                    } else {
                        primary.append('K'); secondary.append('K')
                    }
                }
                'D' -> { primary.append('T'); secondary.append('T') }
                'F' -> { primary.append('F'); secondary.append('F') }
                'G' -> {
                    if (i + 1 < input.length && (input[i + 1] == 'E' || input[i + 1] == 'I' || input[i + 1] == 'Y')) {
                        primary.append('J'); secondary.append('K')
                    } else {
                        primary.append('K'); secondary.append('K')
                    }
                }
                'H' -> {}
                'J' -> { primary.append('J'); secondary.append('J') }
                'K' -> { primary.append('K'); secondary.append('K') }
                'L' -> { primary.append('L'); secondary.append('L') }
                'M' -> { primary.append('M'); secondary.append('M') }
                'N' -> { primary.append('N'); secondary.append('N') }
                'P' -> {
                    if (i + 1 < input.length && input[i + 1] == 'H') {
                        primary.append('F'); secondary.append('F')
                        i++
                    } else {
                        primary.append('P'); secondary.append('P')
                    }
                }
                'Q' -> { primary.append('K'); secondary.append('K') }
                'R' -> { primary.append('R'); secondary.append('R') }
                'S' -> {
                    if (i + 1 < input.length && input[i + 1] == 'H') {
                        primary.append('X'); secondary.append('X')
                        i++
                    } else {
                        primary.append('S'); secondary.append('S')
                    }
                }
                'T' -> {
                    if (i + 1 < input.length && input[i + 1] == 'H') {
                        primary.append('T'); secondary.append('T')
                        i++
                    } else {
                        primary.append('T'); secondary.append('T')
                    }
                }
                'V' -> { primary.append('F'); secondary.append('F') }
                'W' -> {}
                'X' -> { primary.append('K'); secondary.append('S') }
                'Z' -> { primary.append('S'); secondary.append('S') }
            }
            i++
        }
        
        val p = primary.toString().padEnd(4, ' ').substring(0, 4).trim()
        val sAlt = secondary.toString().padEnd(4, ' ').substring(0, 4).trim()
        
        return p to (if (sAlt == p) null else sAlt)
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
}
