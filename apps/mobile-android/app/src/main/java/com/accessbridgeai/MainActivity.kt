package com.accessbridgeai

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.accessbridgeai.data.SessionManager
import com.accessbridgeai.ui.OnboardingViewModel
import com.accessbridgeai.ui.screens.*
import com.accessbridgeai.ui.theme.AccessbridgeAIITheme
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    private lateinit var sessionManager: SessionManager
    private val onboardingViewModel: OnboardingViewModel by viewModels()
    private var isAccessibilityActive by mutableStateOf(false)
    private var isBatteryOptimizationIgnored by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionManager = SessionManager(this)
        enableEdgeToEdge()
        
        setContent {
            AccessbridgeAIITheme {
                val navController = rememberNavController()
                
                val serviceInstance by AccessibilityAgentService.serviceInstance.collectAsState()
                val partialTranscript by serviceInstance?.partialTranscript?.collectAsState("") ?: remember { mutableStateOf("") }
                val speechState by serviceInstance?.speechState?.collectAsState("IDLE") ?: remember { mutableStateOf("IDLE") }
                val connectionStatus by serviceInstance?.connectionStatus?.collectAsState(false) ?: remember { mutableStateOf(false) }
                
                val startDestination = if (sessionManager.isLoggedIn) "dashboard" else "welcome"

                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { isGranted ->
                    if (isGranted) {
                        AccessibilityAgentService.instance?.startAssistant()
                    }
                }

                NavHost(navController = navController, startDestination = startDestination) {
                    composable("welcome") {
                        WelcomeScreen(
                            onGetStarted = { navController.navigate("language") },
                            onSignIn = { navController.navigate("login") }
                        )
                    }
                    composable("language") {
                        LanguageScreen(
                            onLanguageSelected = { lang ->
                                onboardingViewModel.selectedLanguage = lang
                                navController.navigate("onboarding")
                            }
                        )
                    }
                    composable("onboarding") {
                        OnboardingFlowScreen(
                            viewModel = onboardingViewModel,
                            onComplete = {
                                finalizeOnboarding()
                                navController.navigate("dashboard") {
                                    popUpTo("welcome") { inclusive = true }
                                }
                            }
                        )
                    }
                    composable("login") {
                        LoginScreen(
                            onLoginSuccess = { phone ->
                                sessionManager.isLoggedIn = true
                                navController.navigate("dashboard") {
                                    popUpTo("welcome") { inclusive = true }
                                }
                            },
                            onNavigateToSignup = { navController.navigate("language") }
                        )
                    }
                    composable("dashboard") {
                        DashboardScreen(
                            userProfile = sessionManager.getUserProfile(),
                            isAccessibilityEnabled = isAccessibilityActive,
                            isBatteryOptimizationIgnored = isBatteryOptimizationIgnored,
                            isConnected = connectionStatus,
                            partialTranscript = partialTranscript,
                            speechState = speechState,
                            onEnableAccessibility = {
                                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                startActivity(intent)
                            },
                            onIgnoreBatteryOptimization = {
                                requestIgnoreBatteryOptimization()
                            },
                            onMicClick = {
                                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                    AccessibilityAgentService.instance?.startAssistant()
                                } else {
                                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                            onLogout = {
                                sessionManager.logout()
                                navController.navigate("welcome") {
                                    popUpTo("dashboard") { inclusive = true }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isAccessibilityActive = isAccessibilityServiceEnabled(this, AccessibilityAgentService::class.java)
        isBatteryOptimizationIgnored = isBatteryOptimizationIgnored(this)
    }

    private fun isBatteryOptimizationIgnored(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    private fun requestIgnoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Log.e("MainActivity", "Error requesting battery optimization ignore", e)
            }
        }
    }

    private fun finalizeOnboarding() {
        val profile = onboardingViewModel.toUserProfile()
        sessionManager.saveUserProfile(profile)
        sessionManager.selectedLanguage = onboardingViewModel.selectedLanguage
        sessionManager.isLoggedIn = true
        sendRegistrationToBackend(profile, onboardingViewModel.selectedLanguage)
    }

    private fun sendRegistrationToBackend(profile: com.accessbridgeai.data.UserProfile, language: String) {
        val payload = JSONObject().apply {
            put("type", "REGISTRATION")
            put("name", profile.name)
            put("email", profile.email)
            put("phone", profile.phone)
            put("address", profile.address)
            put("language", language)
            put("guardian", JSONObject().apply {
                put("name", profile.guardianName)
                put("email", profile.guardianEmail)
                put("phone", profile.guardianPhone)
                put("notifications", profile.notificationPrefs.joinToString(","))
            })
        }
        Log.d("MainActivity", "Registration Payload: $payload")
        AccessibilityAgentService.instance?.sendPayload(payload)
    }
}

fun isAccessibilityServiceEnabled(context: Context, serviceClass: Class<*>): Boolean {
    val expectedComponentName = ComponentName(context, serviceClass).flattenToString()
    val enabledServicesSetting = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false

    val colonSplitter = TextUtils.SimpleStringSplitter(':')
    colonSplitter.setString(enabledServicesSetting)
    while (colonSplitter.hasNext()) {
        val componentName = colonSplitter.next()
        if (componentName.equals(expectedComponentName, ignoreCase = true)) {
            return true
        }
    }
    return false
}
