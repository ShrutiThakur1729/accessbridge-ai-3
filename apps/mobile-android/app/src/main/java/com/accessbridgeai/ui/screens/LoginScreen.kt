package com.accessbridgeai.ui.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.accessbridgeai.data.SessionManager

// AccessBridge dark theme colors
private val DarkBackground = Color(0xFF0B1120)
private val CardBackground = Color(0xFF151E2E)
private val FieldBackground = Color(0xFF101827)
private val AccentBlue = Color(0xFF38BDF8)
private val PrimaryText = Color(0xFFF1F5F9)
private val SecondaryText = Color(0xFF94A3B8)
private val BorderColor = Color(0xFF334155)

@Composable
fun LoginScreen(
    sessionManager: SessionManager? = null,
    onLoginSuccess: (String) -> Unit,
    onNavigateToSignup: () -> Unit
) {
    val context = LocalContext.current
    var mobileNumber by remember { mutableStateOf("") }
    var otpSent by remember { mutableStateOf(false) }
    var otp by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            // App branding
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = AccentBlue.copy(alpha = 0.12f),
                modifier = Modifier.size(64.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Phone,
                        contentDescription = "AccessBridge",
                        tint = AccentBlue,
                        modifier = Modifier.size(30.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Welcome Back",
                color = PrimaryText,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Text(
                text = "Your bridge to simpler, safer digital access.",
                color = SecondaryText,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp)
            )

            Spacer(modifier = Modifier.height(36.dp))

            // Login card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = CardBackground,
                border = BorderStroke(
                    1.dp,
                    BorderColor.copy(alpha = 0.65f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(20.dp)
                ) {
                    Text(
                        text = "Sign in with your phone",
                        color = PrimaryText,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    Text(
                        text = "We'll help you get started securely.",
                        color = SecondaryText,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 6.dp, bottom = 20.dp)
                    )

                    OutlinedTextField(
                        value = mobileNumber,
                        onValueChange = {
                            if (it.length <= 10 && it.all(Char::isDigit)) {
                                mobileNumber = it
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Mobile number") },
                        placeholder = { Text("Enter 10-digit number") },
                        prefix = { Text("+91", color = SecondaryText) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Phone,
                                contentDescription = null,
                                tint = AccentBlue
                            )
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = PrimaryText,
                            unfocusedTextColor = PrimaryText,
                            focusedContainerColor = FieldBackground,
                            unfocusedContainerColor = FieldBackground,
                            focusedBorderColor = AccentBlue,
                            unfocusedBorderColor = BorderColor,
                            focusedLabelColor = AccentBlue,
                            unfocusedLabelColor = SecondaryText,
                            cursorColor = AccentBlue,
                            focusedPlaceholderColor = SecondaryText,
                            unfocusedPlaceholderColor = SecondaryText
                        )
                    )

                    if (otpSent) {
                        Spacer(modifier = Modifier.height(16.dp))

                        OutlinedTextField(
                            value = otp,
                            onValueChange = {
                                if (it.length <= 6 && it.all(Char::isDigit)) {
                                    otp = it
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Enter OTP") },
                            placeholder = { Text("6-digit code") },
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = PrimaryText,
                                unfocusedTextColor = PrimaryText,
                                focusedContainerColor = FieldBackground,
                                unfocusedContainerColor = FieldBackground,
                                focusedBorderColor = AccentBlue,
                                unfocusedBorderColor = BorderColor,
                                focusedLabelColor = AccentBlue,
                                unfocusedLabelColor = SecondaryText,
                                cursorColor = AccentBlue,
                                focusedPlaceholderColor = SecondaryText,
                                unfocusedPlaceholderColor = SecondaryText
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = {
                            if (mobileNumber.length < 10) {
                                Toast.makeText(context, "Please enter a valid 10-digit mobile number", Toast.LENGTH_SHORT).show()
                                return@Button
                            }

                            if (!otpSent) {
                                isLoading = true
                                if (sessionManager != null) {
                                    sessionManager.sendOtp(mobileNumber) { success, err ->
                                        isLoading = false
                                        if (success) {
                                            otpSent = true
                                        } else {
                                            // Fallback to offline OTP screen
                                            otpSent = true
                                            Toast.makeText(context, "Supabase Auth fallback: Enter 6-digit OTP", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                } else {
                                    otpSent = true
                                    isLoading = false
                                }
                            } else {
                                isLoading = true
                                if (sessionManager != null && otp.length == 6) {
                                    sessionManager.verifyOtp(mobileNumber, otp) { success, err ->
                                        isLoading = false
                                        if (success) {
                                            onLoginSuccess(mobileNumber)
                                        } else {
                                            Toast.makeText(context, "OTP Verification Failed: ${err ?: "Invalid OTP"}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                } else {
                                    isLoading = false
                                    if (otp.length == 6) {
                                        onLoginSuccess(mobileNumber)
                                    } else {
                                        Toast.makeText(context, "Please enter 6-digit OTP", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        },
                        enabled = !isLoading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentBlue,
                            contentColor = DarkBackground
                        )
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(color = DarkBackground, modifier = Modifier.size(24.dp))
                        } else {
                            Text(
                                text = if (otpSent) "Verify & Login" else "Send OTP",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HorizontalDivider(
                            modifier = Modifier.weight(1f),
                            color = BorderColor
                        )
                        Text(
                            text = "  OR  ",
                            color = SecondaryText,
                            fontSize = 12.sp
                        )
                        HorizontalDivider(
                            modifier = Modifier.weight(1f),
                            color = BorderColor
                        )
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    OutlinedButton(
                        onClick = {
                            Toast.makeText(context, "Google OAuth: Configure SHA-1 in Supabase dashboard", Toast.LENGTH_LONG).show()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            BorderColor
                        ),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = PrimaryText
                        )
                    ) {
                        Text(
                            text = "Continue with Google",
                            fontSize = 14.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "New to AccessBridge?",
                    color = SecondaryText,
                    fontSize = 14.sp
                )

                TextButton(onClick = onNavigateToSignup) {
                    Text(
                        text = "Get Started",
                        color = AccentBlue,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Security information
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = CardBackground,
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    BorderColor.copy(alpha = 0.65f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Default.Phone,
                        contentDescription = null,
                        tint = AccentBlue,
                        modifier = Modifier.size(22.dp)
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = "Your safety matters",
                            color = PrimaryText,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )

                        Text(
                            text = "AccessBridge is designed to make digital tasks simpler and safer.",
                            color = SecondaryText,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
