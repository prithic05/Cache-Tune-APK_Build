package com.cachetune.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cachetune.app.ui.components.GlassmorphicCard
import com.cachetune.app.ui.theme.*

/**
 * Aesthetic Settings & Authentication Screen with Cyberpunk styling.
 * Supports direct on-device credential storage & Cloud/Laptop Docker testing.
 */
@Composable
fun SettingsScreen(
    currentYtHeaders: String,
    currentAppleToken: String,
    currentAppleUsername: String,
    currentStorefront: String,
    currentWrapperHost: String,
    currentBackendUrl: String,
    backendStatus: String? = null,
    isTestingBackend: Boolean = false,
    onTestBackend: () -> Unit = {},
    onSaveYtHeaders: (String) -> Unit,
    onSaveAppleConfig: (username: String, pass: String, token: String, storefront: String, wrapperHost: String) -> Unit,
    onSaveBackendUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var ytHeadersInput by remember(currentYtHeaders) { mutableStateOf(currentYtHeaders) }
    var appleUsernameInput by remember(currentAppleUsername) { mutableStateOf(currentAppleUsername) }
    var applePasswordInput by remember { mutableStateOf("") }
    var appleTokenInput by remember(currentAppleToken) { mutableStateOf(currentAppleToken) }
    var storefrontInput by remember(currentStorefront) { mutableStateOf(currentStorefront) }
    var wrapperHostInput by remember(currentWrapperHost) { mutableStateOf(currentWrapperHost) }
    var backendUrlInput by remember(currentBackendUrl) { mutableStateOf(currentBackendUrl) }

    var saveMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Column {
            Text(
                text = "CONFIGURATION",
                style = MaterialTheme.typography.labelSmall.copy(
                    letterSpacing = 3.sp,
                    color = NeonCyan,
                    fontWeight = FontWeight.Bold
                )
            )
            Text(
                text = "Settings & Credentials",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                    color = TextPrimary
                )
            )
        }

        saveMessage?.let {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = NeonCyan.copy(alpha = 0.2f),
                border = androidx.compose.foundation.BorderStroke(1.dp, NeonCyan)
            ) {
                Text(
                    text = it,
                    color = NeonCyan,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        // Section 1: Cyber Cloud Engine (Docker / Laptop)
        Text(
            text = "⚡ CYBER CLOUD ENGINE (DOCKER / LAPTOP)",
            style = MaterialTheme.typography.labelSmall.copy(
                letterSpacing = 2.sp,
                color = NeonCyan,
                fontWeight = FontWeight.Bold
            )
        )

        GlassmorphicCard(
            shape = RoundedCornerShape(18.dp),
            backgroundColor = SurfaceDark,
            borderColor = NeonCyan.copy(alpha = 0.4f)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Studio FLAC Decryption Bridge",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = TextPrimary)
                )
                Text(
                    text = "On-device search and streaming run 100% locally. Point to your laptop Docker container or Oracle Cloud VM to enable 1-tap 24-bit/192kHz DRM decryption & FLAC conversion.",
                    style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                )

                OutlinedTextField(
                    value = backendUrlInput,
                    onValueChange = { backendUrlInput = it },
                    label = { Text("Cloud / Laptop URL", color = TextSecondary) },
                    placeholder = { Text("https://cachetune.dpdns.org or http://192.168.1.x:8000", color = TextTertiary) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = NeonCyan,
                        unfocusedBorderColor = SurfaceBorder
                    )
                )

                backendStatus?.let { status ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (status.startsWith("⚡")) NeonCyan.copy(alpha = 0.15f) else Color(0x33FF0055),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (status.startsWith("⚡")) NeonCyan else Color(0xFFFF0055)
                        )
                    ) {
                        Text(
                            text = status,
                            color = if (status.startsWith("⚡")) NeonCyan else Color(0xFFFF4D79),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onTestBackend,
                        enabled = !isTestingBackend,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonCyan),
                        border = androidx.compose.foundation.BorderStroke(1.dp, NeonCyan),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isTestingBackend) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = NeonCyan, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Testing...")
                        } else {
                            Icon(Icons.Rounded.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Test Link", fontWeight = FontWeight.Bold)
                        }
                    }

                    Button(
                        onClick = {
                            onSaveBackendUrl(backendUrlInput)
                            saveMessage = "Cloud engine URL saved successfully!"
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = BackgroundDark),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Save URL", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Section 2: YouTube Music Authentication
        Text(
            text = "▶ YOUTUBE MUSIC AUTHENTICATION",
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp, color = NeonMagenta)
        )

        GlassmorphicCard(
            shape = RoundedCornerShape(18.dp),
            backgroundColor = SurfaceDark,
            borderColor = SurfaceBorder
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Personalized Recommendations",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = TextPrimary)
                )
                Text(
                    text = "Paste your YouTube Music cookie or DevTools headers to load your personal feed, favorites, and history on-device.",
                    style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                )

                OutlinedTextField(
                    value = ytHeadersInput,
                    onValueChange = { ytHeadersInput = it },
                    label = { Text("Cookie / Raw Headers", color = TextSecondary) },
                    modifier = Modifier.fillMaxWidth().height(110.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = NeonMagenta,
                        unfocusedBorderColor = SurfaceBorder
                    )
                )

                Button(
                    onClick = {
                        onSaveYtHeaders(ytHeadersInput)
                        saveMessage = "YouTube Music credentials saved successfully!"
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonMagenta, contentColor = Color.White),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Save YT Auth", fontWeight = FontWeight.Bold)
                }
            }
        }

        // Section 3: Apple Music Credentials & Decryptor
        Text(
            text = "🍎 APPLE MUSIC & DECRYPTOR",
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp, color = ElectricBlue)
        )

        GlassmorphicCard(
            shape = RoundedCornerShape(18.dp),
            backgroundColor = SurfaceDark,
            borderColor = SurfaceBorder
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Lossless Apple Music Token",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = TextPrimary)
                )
                Text(
                    text = "Configure your media-user-token and storefront to fetch high-res Apple Music artwork and stream metadata.",
                    style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                )

                OutlinedTextField(
                    value = appleTokenInput,
                    onValueChange = { appleTokenInput = it },
                    label = { Text("Media-User-Token", color = TextSecondary) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = ElectricBlue,
                        unfocusedBorderColor = SurfaceBorder
                    )
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = storefrontInput,
                        onValueChange = { storefrontInput = it },
                        label = { Text("Storefront (e.g. in, us)", color = TextSecondary) },
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = ElectricBlue,
                            unfocusedBorderColor = SurfaceBorder
                        )
                    )

                    OutlinedTextField(
                        value = wrapperHostInput,
                        onValueChange = { wrapperHostInput = it },
                        label = { Text("Wrapper Host:Port", color = TextSecondary) },
                        placeholder = { Text("127.0.0.1:10020", color = TextTertiary) },
                        modifier = Modifier.weight(1.5f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = ElectricBlue,
                            unfocusedBorderColor = SurfaceBorder
                        )
                    )
                }

                Button(
                    onClick = {
                        onSaveAppleConfig(appleUsernameInput, applePasswordInput, appleTokenInput, storefrontInput, wrapperHostInput)
                        saveMessage = "Apple Music settings saved successfully!"
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue, contentColor = BackgroundDark),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Save Apple Config", fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(100.dp))
    }
}
