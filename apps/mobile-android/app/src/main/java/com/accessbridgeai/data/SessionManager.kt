package com.accessbridgeai.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import java.util.UUID

class SessionManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("access_bridge_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val KEY_IS_LOGGED_IN = "is_logged_in"
        private const val KEY_USER_PROFILE = "user_profile"
        private const val KEY_SELECTED_LANGUAGE = "selected_language"
        private const val KEY_SESSION_ID = "session_id"
    }

    var isLoggedIn: Boolean
        get() = prefs.getBoolean(KEY_IS_LOGGED_IN, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_LOGGED_IN, value).apply()

    var selectedLanguage: String?
        get() = prefs.getString(KEY_SELECTED_LANGUAGE, "en")
        set(value) = prefs.edit().putString(KEY_SELECTED_LANGUAGE, value).apply()

    fun saveUserProfile(profile: UserProfile) {
        val json = gson.toJson(profile)
        prefs.edit().putString(KEY_USER_PROFILE, json).apply()
    }

    fun getUserProfile(): UserProfile? {
        val json = prefs.getString(KEY_USER_PROFILE, null)
        return if (json != null) {
            gson.fromJson(json, UserProfile::class.java)
        } else {
            null
        }
    }

    fun logout() {
        prefs.edit().clear().apply()
    }

    fun getSessionId(): String {
        var sessionId = prefs.getString(KEY_SESSION_ID, null)
        if (sessionId == null) {
            sessionId = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_SESSION_ID, sessionId).apply()
        }
        return sessionId
    }

    fun resetSession() {
        val newSessionId = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_SESSION_ID, newSessionId).apply()
    }
}

data class UserProfile(
    val name: String,
    val email: String,
    val phone: String,
    val address: String,
    val guardianName: String? = null,
    val guardianEmail: String? = null,
    val guardianPhone: String? = null,
    val notificationPrefs: List<String> = emptyList()
)
