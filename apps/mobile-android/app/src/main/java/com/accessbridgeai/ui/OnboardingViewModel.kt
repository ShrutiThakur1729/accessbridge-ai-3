package com.accessbridgeai.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.accessbridgeai.data.UserProfile

class OnboardingViewModel : ViewModel() {
    var fullName by mutableStateOf("")
    var email by mutableStateOf("")
    var mobileNumber by mutableStateOf("")
    var address by mutableStateOf("")

    var guardianName by mutableStateOf("")
    var guardianEmail by mutableStateOf("")
    var guardianPhone by mutableStateOf("")
    var notificationPrefs by mutableStateOf(setOf<String>())

    var selectedLanguage by mutableStateOf("en")
    
    var hasAgreedToSafety by mutableStateOf(false)

    fun toUserProfile(): UserProfile {
        return UserProfile(
            name = fullName,
            email = email,
            phone = mobileNumber,
            address = address,
            guardianName = guardianName,
            guardianEmail = guardianEmail,
            guardianPhone = guardianPhone,
            notificationPrefs = notificationPrefs.toList()
        )
    }
}
