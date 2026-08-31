  package com.accessbridgeai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.accessbridgeai.data.UserProfile

@Composable
fun DashboardScreen(
    userProfile: UserProfile?,
    isAccessibilityEnabled: Boolean,
    isBatteryOptimizationIgnored: Boolean = true,
    isConnected: Boolean = false,
    partialTranscript: String = "",
    speechState: String = "IDLE",
    onEnableAccessibility: () -> Unit,
    onIgnoreBatteryOptimization: () -> Unit = {},
    onMicClick: () -> Unit,
    onLogout: () -> Unit
) {
    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Person, contentDescription = null)
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = userProfile?.name ?: "User",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(
                                        if (isConnected) Color(0xFF4CAF50) else Color(0xFFF44336),
                                        CircleShape
                                    )
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isConnected) "Connected" else "Disconnected",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                IconButton(onClick = onLogout) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!isAccessibilityEnabled) {
                Surface(
                    color = Color(0xFFFFF3E0),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null, tint = Color(0xFFEF6C00))
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Accessibility Service Disabled",
                                style = MaterialTheme.typography.labelLarge,
                                color = Color(0xFFE65100),
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Enable it to allow the assistant to interact with other apps.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFEF6C00)
                            )
                        }
                        Button(
                            onClick = onEnableAccessibility,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF6C00)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("Enable", fontSize = 12.sp)
                        }
                    }
                }
            }

            if (!isBatteryOptimizationIgnored) {
                Surface(
                    color = Color(0xFFE8EAF6),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null, tint = Color(0xFF3F51B5))
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Battery Optimization Active",
                                style = MaterialTheme.typography.labelLarge,
                                color = Color(0xFF283593),
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Disable to prevent service termination in background.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF3F51B5)
                            )
                        }
                        Button(
                            onClick = onIgnoreBatteryOptimization,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3F51B5)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("Fix", fontSize = 12.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(200.dp)
            ) {
                // Outer glow effect (simplified)
                Surface(
                    shape = CircleShape,
                    color = Color(0xFFE3F2FD),
                    modifier = Modifier.fillMaxSize()
                ) {}
                
                Surface(
                    shape = CircleShape,
                    color = Color(0xFF2196F3),
                    modifier = Modifier
                        .size(140.dp)
                        .clickable { onMicClick() },
                    shadowElevation = 8.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = "Speak",
                            modifier = Modifier.size(64.dp),
                            tint = Color.White
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
            
            Text(
                text = when {
                    speechState == "SPEAKING" -> "Assistant is speaking..."
                    speechState == "THINKING" -> "Processing..."
                    partialTranscript.isNotEmpty() -> partialTranscript
                    speechState == "LISTENING" -> "I'm listening..."
                    else -> "Ready to Listen"
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            
            Text(
                text = when {
                    speechState == "LISTENING" -> "Listening..."
                    speechState == "SPEAKING" -> "Responding to your request"
                    speechState == "THINKING" -> "Finding the best way to help"
                    else -> "Tap the microphone and tell me what you need help with."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp)
            )

            Spacer(modifier = Modifier.weight(1.5f))
        }
    }
}
