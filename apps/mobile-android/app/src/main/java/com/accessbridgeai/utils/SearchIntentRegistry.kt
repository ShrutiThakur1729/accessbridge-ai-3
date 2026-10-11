package com.accessbridgeai.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object SearchIntentRegistry {

    private const val TAG = "SearchIntentRegistry"

    /**
     * Checks if a package has a specialized in-app search intent registered.
     */
    fun hasSearchSupport(packageName: String): Boolean {
        return packageName == "com.google.android.youtube"
    }

    /**
     * Attempts to create and launch an in-app search intent for supported packages.
     * 
     * Returns true if an intent was resolved and successfully launched, false if resolution/launch failed,
     * or null if the package is not supported by the registry.
     * 
     * MUST NOT log the query text to protect user privacy.
     */
    fun launchInAppSearch(context: Context, packageName: String, query: String): Boolean? {
        if (!hasSearchSupport(packageName)) {
            return null
        }

        if (query.isBlank()) {
            Log.w(TAG, "Search query is blank for package: $packageName")
            return null
        }

        val pm = context.packageManager

        when (packageName) {
            "com.google.android.youtube" -> {
                // Primary: ACTION_SEARCH intent
                val searchIntent = Intent(Intent.ACTION_SEARCH).apply {
                    setPackage(packageName)
                    putExtra("query", query)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                if (searchIntent.resolveActivity(pm) != null) {
                    return try {
                        context.startActivity(searchIntent)
                        Log.i(TAG, "Form used: ACTION_SEARCH for package $packageName. Launched: true")
                        true
                    } catch (e: Exception) {
                        Log.e(TAG, "Form used: ACTION_SEARCH for package $packageName. Launched: false (error: ${e.message})")
                        false
                    }
                } else {
                    Log.w(TAG, "ACTION_SEARCH intent did not resolve for package $packageName. Trying ACTION_VIEW fallback.")
                }

                // Fallback: ACTION_VIEW with web URL search query
                val encodedQuery = try {
                    URLEncoder.encode(query, StandardCharsets.UTF_8.name())
                } catch (e: Exception) {
                    query
                }
                val viewIntent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://www.youtube.com/results?search_query=$encodedQuery")
                ).apply {
                    setPackage(packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                if (viewIntent.resolveActivity(pm) != null) {
                    return try {
                        context.startActivity(viewIntent)
                        Log.i(TAG, "Form used: ACTION_VIEW fallback for package $packageName. Launched: true")
                        true
                    } catch (e: Exception) {
                        Log.e(TAG, "Form used: ACTION_VIEW fallback for package $packageName. Launched: false (error: ${e.message})")
                        false
                    }
                } else {
                    Log.w(TAG, "ACTION_VIEW intent did not resolve for package $packageName. Launch failed.")
                    return false
                }
            }
            else -> return null
        }
    }
}
