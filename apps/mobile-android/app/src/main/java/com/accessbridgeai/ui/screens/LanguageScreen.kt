package com.accessbridgeai.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LanguageScreen(
    onLanguageSelected: (String) -> Unit
) {
    var selectedLanguage by remember { mutableStateOf("en") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        IconButton(onClick = { /* Back */ }) {
            Icon(Icons.Default.Language, contentDescription = "Back")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Choose Language",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Welcome to Access Bridge",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = "Please select your preferred language to continue the setup.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Surface(
            color = Color(0xFFE3F2FD),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Language,
                    contentDescription = null,
                    tint = Color(0xFF2196F3)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "This will be the primary language used for all voice commands and safety alerts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF1976D2)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        LanguageCard(
            title = "English",
            subtitle = "(English)",
            icon = Icons.Default.Language,
            isSelected = selectedLanguage == "en",
            onClick = { selectedLanguage = "en" }
        )

        Spacer(modifier = Modifier.height(16.dp))

        LanguageCard(
            title = "Hindi",
            subtitle = "(हिंदी)",
            icon = Icons.Default.Translate,
            isSelected = selectedLanguage == "hi",
            onClick = { selectedLanguage = "hi" }
        )

        Spacer(modifier = Modifier.height(16.dp))

        LanguageCard(
            title = "Marathi",
            subtitle = "(मराठी)",
            icon = Icons.Default.Translate,
            isSelected = selectedLanguage == "mr",
            onClick = { selectedLanguage = "mr" }
        )

        Spacer(modifier = Modifier.weight(1f))

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

        Text(
            text = "SECURE ONBOARDING • STEP 1 OF 4",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
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
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            color = if (isSelected) Color(0xFF2ECC71) else Color.LightGray
        ),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (isSelected) Color(0xFFE8F5E9) else Color(0xFFF5F5F5),
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = if (isSelected) Color(0xFF2ECC71) else Color.Gray
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
