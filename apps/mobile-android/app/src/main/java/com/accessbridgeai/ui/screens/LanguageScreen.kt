package com.accessbridgeai.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class LanguageOption(
    val code: String,
    val nameInEnglish: String,
    val nativeScript: String,
    val isVerified: Boolean = true,
    val icon: ImageVector = Icons.Default.Translate
)

val SUPPORTED_LANGUAGES = listOf(
    LanguageOption("en", "English", "English", isVerified = true, icon = Icons.Default.Language),
    LanguageOption("hi", "Hindi", "हिन्दी", isVerified = true),
    LanguageOption("mr", "Marathi", "मराठी", isVerified = true),
    LanguageOption("bn", "Bengali", "বাংলা", isVerified = false),
    LanguageOption("ta", "Tamil", "தமிழ்", isVerified = false),
    LanguageOption("te", "Telugu", "తెలుగు", isVerified = false),
    LanguageOption("kn", "Kannada", "ಕನ್ನಡ", isVerified = false),
    LanguageOption("gu", "Gujarati", "ગુજરાતી", isVerified = false),
    LanguageOption("pa", "Punjabi", "ਪੰਜਾਬੀ", isVerified = false),
    LanguageOption("ml", "Malayalam", "മലയാളം", isVerified = false)
)

@Composable
fun LanguageScreen(
    onLanguageSelected: (String) -> Unit
) {
    var selectedLanguage by remember { mutableStateOf("en") }
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(scrollState)
    ) {
        IconButton(onClick = { /* Back */ }) {
            Icon(Icons.Default.Language, contentDescription = "Back")
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Choose Language",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Welcome to Access Bridge",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = "Select your preferred language for assistant responses.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Surface(
            color = Color(0xFFE3F2FD),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Language,
                    contentDescription = null,
                    tint = Color(0xFF2196F3)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Your preference is saved to your profile and used for responses.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF1976D2)
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            SUPPORTED_LANGUAGES.forEach { lang ->
                LanguageCard(
                    title = lang.nameInEnglish,
                    subtitle = if (lang.isVerified) "(${lang.nativeScript})" else "(${lang.nativeScript} • Coming Soon)",
                    icon = lang.icon,
                    isSelected = selectedLanguage == lang.code,
                    isEnabled = lang.isVerified,
                    onClick = {
                        if (lang.isVerified) {
                            selectedLanguage = lang.code
                        }
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = { onLanguageSelected(selectedLanguage) },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2ECC71))
        ) {
            Text(text = "Continue", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "SECURE ONBOARDING • STEP 1 OF 4",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun LanguageCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    isSelected: Boolean,
    isEnabled: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            color = when {
                isSelected -> Color(0xFF2ECC71)
                !isEnabled -> Color.LightGray.copy(alpha = 0.5f)
                else -> Color.LightGray
            }
        ),
        color = if (isEnabled) MaterialTheme.colorScheme.surface else Color(0xFFFAFAFA),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(14.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = when {
                    isSelected -> Color(0xFFE8F5E9)
                    !isEnabled -> Color(0xFFEEEEEE)
                    else -> Color(0xFFF5F5F5)
                },
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = when {
                            isSelected -> Color(0xFF2ECC71)
                            !isEnabled -> Color.LightGray
                            else -> Color.Gray
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isEnabled) MaterialTheme.colorScheme.onSurface else Color.Gray
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isEnabled) MaterialTheme.colorScheme.onSurfaceVariant else Color.LightGray
                )
            }

            if (isSelected) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF2ECC71)
                )
            }
        }
    }
}
