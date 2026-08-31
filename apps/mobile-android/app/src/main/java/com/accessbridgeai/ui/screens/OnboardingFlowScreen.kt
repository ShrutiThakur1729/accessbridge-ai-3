package com.accessbridgeai.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.accessbridgeai.ui.OnboardingViewModel

@Composable
fun OnboardingFlowScreen(
    viewModel: OnboardingViewModel,
    onComplete: () -> Unit
) {
    var currentStep by remember { mutableStateOf(1) }
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(scrollState)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(onClick = { if (currentStep > 1) currentStep-- }) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = when (currentStep) {
                    1 -> "User Details"
                    2 -> "Digital Setu"
                    3 -> "Safety Promise"
                    else -> ""
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.width(48.dp))
        }

        LinearProgressIndicator(
            progress = { currentStep / 3f },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            color = Color(0xFF2ECC71),
            trackColor = Color(0xFFE8F5E9)
        )

        when (currentStep) {
            1 -> UserDetailsStep(viewModel) { currentStep = 2 }
            2 -> GuardianDetailsStep(viewModel) { currentStep = 3 }
            3 -> SafetyPromiseStep(viewModel, onComplete)
        }
    }
}

@Composable
fun UserDetailsStep(viewModel: OnboardingViewModel, onNext: () -> Unit) {
    Column {
        Text("Identity Setup", style = MaterialTheme.typography.titleMedium, color = Color(0xFF2196F3))
        Spacer(modifier = Modifier.height(24.dp))
        
        OutlinedTextField(
            value = viewModel.fullName,
            onValueChange = { viewModel.fullName = it },
            label = { Text("Full Name") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        
        OutlinedTextField(
            value = viewModel.email,
            onValueChange = { viewModel.email = it },
            label = { Text("Email Address") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = viewModel.mobileNumber,
                onValueChange = { viewModel.mobileNumber = it },
                label = { Text("Mobile Number") },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                prefix = { Text("+91 ") }
            )
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(onClick = { /* Trigger OTP */ }) {
                Text("Send OTP", color = Color(0xFF2196F3))
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        
        OutlinedTextField(
            value = viewModel.address,
            onValueChange = { viewModel.address = it },
            label = { Text("Full Address") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            minLines = 3
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Button(
            onClick = onNext,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3))
        ) {
            Text("Continue to Guardian Details", fontSize = 16.sp)
            Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
fun GuardianDetailsStep(viewModel: OnboardingViewModel, onNext: () -> Unit) {
    Column {
        Text("Guardian Details", style = MaterialTheme.typography.titleMedium)
        Text(
            "A guardian will ensure account safety and provide assistance when needed.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )
        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = viewModel.guardianName,
            onValueChange = { viewModel.guardianName = it },
            label = { Text("Guardian Full Name") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) }
        )
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = viewModel.guardianEmail,
            onValueChange = { viewModel.guardianEmail = it },
            label = { Text("Guardian Email Address") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) }
        )
        Spacer(modifier = Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = viewModel.guardianPhone,
                onValueChange = { viewModel.guardianPhone = it },
                label = { Text("Guardian Mobile Number") },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                prefix = { Text("+91 ") }
            )
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(onClick = { /* Trigger OTP */ }) {
                Text("Send OTP", color = Color(0xFF2196F3))
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        Text("Where would you like to be notified?", style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NotificationChip("SMS", viewModel.notificationPrefs.contains("SMS")) {
                viewModel.notificationPrefs = if (it) viewModel.notificationPrefs + "SMS" else viewModel.notificationPrefs - "SMS"
            }
            NotificationChip("WhatsApp", viewModel.notificationPrefs.contains("WhatsApp")) {
                viewModel.notificationPrefs = if (it) viewModel.notificationPrefs + "WhatsApp" else viewModel.notificationPrefs - "WhatsApp"
            }
            NotificationChip("Email", viewModel.notificationPrefs.contains("Email")) {
                viewModel.notificationPrefs = if (it) viewModel.notificationPrefs + "Email" else viewModel.notificationPrefs - "Email"
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
        
        Surface(
            color = Color(0xFFE8F5E9),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Review & Confirm", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("USER NAME", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        Text(viewModel.fullName, style = MaterialTheme.typography.bodySmall)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("GUARDIAN NAME", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        Text(viewModel.guardianName, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        
        Button(
            onClick = onNext,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2ECC71))
        ) {
            Text("Finish Setup", fontSize = 16.sp)
            Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
fun NotificationChip(label: String, isSelected: Boolean, onSelectedChange: (Boolean) -> Unit) {
    Surface(
        onClick = { onSelectedChange(!isSelected) },
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, if (isSelected) Color(0xFF2ECC71) else Color.LightGray),
        color = if (isSelected) Color(0xFFE8F5E9) else Color.Transparent
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            color = if (isSelected) Color(0xFF2ECC71) else Color.Gray,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
fun SafetyPromiseStep(viewModel: OnboardingViewModel, onComplete: () -> Unit) {
    Column {
        Text("Our Commitment to Your Safety", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(16.dp))
        
        SafetyItem(Icons.Default.Lock, "Data Privacy First", "We use military-grade encryption. Your personal data and voice logs are never sold.")
        SafetyItem(Icons.Default.People, "Guardian Oversight", "Your safety net is always active. Your guardian can assist with complex tasks.")
        SafetyItem(Icons.Default.Error, "Instant Emergency SOS", "Quick access SOS triggers automated location sharing.")
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = viewModel.hasAgreedToSafety,
                onCheckedChange = { viewModel.hasAgreedToSafety = it }
            )
            Text(
                "I have read the Safety Protocols and agree to activate my guardian-led account...",
                style = MaterialTheme.typography.bodySmall
            )
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Button(
            onClick = onComplete,
            enabled = viewModel.hasAgreedToSafety,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2ECC71))
        ) {
            Text("Agree & Activate", fontSize = 16.sp)
        }
    }
}

@Composable
fun SafetyItem(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, description: String) {
    Row(modifier = Modifier.padding(vertical = 8.dp)) {
        Icon(icon, contentDescription = null, tint = Color(0xFF2196F3))
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(title, fontWeight = FontWeight.Bold)
            Text(description, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
    }
}
