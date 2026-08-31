package com.accessbridgeai

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

object AppResolver {
    private const val TAG = "AppResolver"

    /**
     * Resolves a package name by searching for an app label (display name).
     * Priority 1: Exact case-insensitive match.
     * Priority 2: Partial case-insensitive match (contains).
     */
    fun findPackageByLabel(context: Context, appLabel: String): String? {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        
        // Use queryIntentActivities for better results with launcher apps
        val apps = pm.queryIntentActivities(mainIntent, 0)
        
        Log.d(TAG, "Querying installed apps for label: '$appLabel'. Total apps found: ${apps.size}")

        // 1. Exact match
        apps.find { 
            it.loadLabel(pm).toString().equals(appLabel, ignoreCase = true) 
        }?.let {
            val pkg = it.activityInfo.packageName
            Log.d(TAG, "Exact match found for '$appLabel': $pkg")
            return pkg
        }
        
        // 2. Partial match
        apps.find { 
            it.loadLabel(pm).toString().contains(appLabel, ignoreCase = true) 
        }?.let {
            val pkg = it.activityInfo.packageName
            Log.d(TAG, "Partial match found for '$appLabel': $pkg")
            return pkg
        }
        
        Log.w(TAG, "No package found for app label: '$appLabel'")
        return null
    }
}
