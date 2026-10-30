package com.cachetune.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cachetune.app.R
import com.cachetune.app.ui.theme.*

/**
 * Clean, organized, modern Configuration Screen.
 * Categorized into sleek tabs: YouTube Music, Docker, and Apple Music.
 * All credentials are encrypted and stored 100% locally on the device.
 */
@Composable
fun ConfigScreen(
    currentYtHeaders: String,
    currentAppleToken: String,
    currentAppleUsername: String,
    currentApplePassword: String = "",
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
    var selectedCategory by remember { mutableIntStateOf(0) } // 0 = YT, 1 = Server, 2 = Apple

    var ytHeadersInput by remember(currentYtHeaders) { mutableStateOf(currentYtHeaders) }
    var backendUrlInput by remember(currentBackendUrl) { mutableStateOf(currentBackendUrl) }
    var appleTokenInput by remember(currentAppleToken) { mutableStateOf(currentAppleToken) }
    var appleUsernameInput by remember(currentAppleUsername) { mutableStateOf(currentAppleUsername) }
    var applePasswordInput by remember(currentApplePassword) { mutableStateOf(currentApplePassword) }
    var storefrontInput by remember(currentStorefront) { mutableStateOf(currentStorefront) }
    var showAppleToken by remember { mutableStateOf(false) }
    var showApplePassword by remember { mutableStateOf(false) }

    var toastMessage by remember { mutableStateOf<String?>(null) }

    val hasYt = ytHeadersInput.isNotBlank()
    val hasServer = backendStatus?.contains("Connected", ignoreCase = true) == true || backendStatus?.contains("200", ignoreCase = true) == true
    val hasApple = appleTokenInput.isNotBlank() || appleUsernameInput.isNotBlank()

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .background(Color(0xFF0A0A0A))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        // 1. Header with Official CacheTune Logo & Title
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_app_logo),
                    contentDescription = "CacheTune Logo",
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
                Column {
                    Text(
                        text = "Settings & Config",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Credentials stored locally on device",
                        color = Color(0xFF888888),
                        fontSize = 11.sp
                    )
                }
            }
        }

        // 2. Status Row: "Docker" (word Engine removed)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ConfigStatusPill(
                label = "YT Feed",
                isActive = hasYt,
                modifier = Modifier.weight(1f)
            )
            ConfigStatusPill(
                label = "Docker",
                isActive = hasServer,
                modifier = Modifier.weight(1f)
            )
            ConfigStatusPill(
                label = "Apple Music",
                isActive = hasApple,
                modifier = Modifier.weight(1f)
            )
        }

        // 3. Category Tabs
        val categories = listOf("YouTube Feed", "Docker Server", "Apple Music")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF161616))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            categories.forEachIndexed { index, title ->
                val isSelected = selectedCategory == index
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) CacheTuneOrange else Color.Transparent)
                        .clickable { selectedCategory = index }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = title,
                        color = if (isSelected) Color.Black else Color(0xFFAAAAAA),
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 12.sp
                    )
                }
            }
        }

        // Toast Feedback Message
        AnimatedVisibility(
            visible = toastMessage != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            toastMessage?.let {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = CacheTuneOrangeMuted,
                    border = BorderStroke(1.dp, CacheTuneOrange),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = it,
                        color = CacheTuneOrangeBright,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }

        // 4. Tab Content Panels
        when (selectedCategory) {
            0 -> {
                // TAB 0: YouTube Music Config
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161616)),
                    border = BorderStroke(1.dp, Color(0xFF262626)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PlayCircle,
                                contentDescription = null,
                                tint = Color(0xFFEF4444),
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "YouTube Music Authentication",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Text(
                            text = "Paste your raw browser request headers JSON from music.youtube.com (includes SAPISID and cookies) for personalized feeds and infinite radio.",
                            color = Color(0xFFAAAAAA),
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )

                        OutlinedTextField(
                            value = ytHeadersInput,
                            onValueChange = { ytHeadersInput = it },
                            placeholder = { Text("{\n  \"cookie\": \"...\",\n  \"authorization\": \"...\"\n}", color = Color(0xFF555555), fontSize = 11.sp, fontFamily = FontFamily.Monospace) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp),
                            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color.White),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CacheTuneOrange,
                                unfocusedBorderColor = Color(0xFF2E2E2E),
                                focusedContainerColor = Color(0xFF0F0F0F),
                                unfocusedContainerColor = Color(0xFF0F0F0F)
                            )
                        )

                        Button(
                            onClick = {
                                onSaveYtHeaders(ytHeadersInput)
                                toastMessage = "✔ YouTube Music headers saved!"
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CacheTuneOrange),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                        ) {
                            Icon(Icons.Rounded.Save, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("SAVE YOUTUBE HEADERS", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }

            1 -> {
                // TAB 1: Docker Server Config
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161616)),
                    border = BorderStroke(1.dp, Color(0xFF262626)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Dns,
                                contentDescription = null,
                                tint = CacheTuneOrange,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "Docker Decryption & Conversion Backend",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Text(
                            text = "URL of your CacheTune Docker backend container handling FairPlay CDM decryption and 24-bit studio FLAC streaming.",
                            color = Color(0xFFAAAAAA),
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )

                        OutlinedTextField(
                            value = backendUrlInput,
                            onValueChange = { backendUrlInput = it },
                            label = { Text("Backend URL", fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color.White),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CacheTuneOrange,
                                unfocusedBorderColor = Color(0xFF2E2E2E),
                                focusedContainerColor = Color(0xFF0F0F0F),
                                unfocusedContainerColor = Color(0xFF0F0F0F)
                            )
                        )

                        if (!backendStatus.isNullOrBlank()) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF1F1F1F),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = backendStatus,
                                    color = if (hasServer) Color(0xFF34D399) else Color(0xFFFBBF24),
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(10.dp)
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
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, CacheTuneOrange),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                            ) {
                                if (isTestingBackend) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = CacheTuneOrange, strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Rounded.Sensors, contentDescription = null, tint = CacheTuneOrange, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("TEST LINK", color = CacheTuneOrange, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }

                            Button(
                                onClick = {
                                    onSaveBackendUrl(backendUrlInput)
                                    toastMessage = "✔ Backend URL saved!"
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = CacheTuneOrange),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                            ) {
                                Text("SAVE URL", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            2 -> {
                // TAB 2: Apple Music Config (Apple ID + Password for wrapper authentication)
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161616)),
                    border = BorderStroke(1.dp, Color(0xFF262626)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.MusicNote,
                                contentDescription = null,
                                tint = CacheTuneOrange,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "Apple Music Lossless Credentials",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Text(
                            text = "Provide your Apple ID and credentials for FairPlay decryption and Hi-Res studio master streaming.",
                            color = Color(0xFFAAAAAA),
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )

                        // Apple ID (Clean input with no personal email placeholder)
                        OutlinedTextField(
                            value = appleUsernameInput,
                            onValueChange = { appleUsernameInput = it },
                            label = { Text("Apple ID", fontSize = 11.sp) },
                            placeholder = { Text("name@example.com", color = Color(0xFF555555), fontSize = 12.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, color = Color.White),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CacheTuneOrange,
                                unfocusedBorderColor = Color(0xFF2E2E2E),
                                focusedContainerColor = Color(0xFF0F0F0F),
                                unfocusedContainerColor = Color(0xFF0F0F0F)
                            )
                        )

                        // Apple Password
                        OutlinedTextField(
                            value = applePasswordInput,
                            onValueChange = { applePasswordInput = it },
                            label = { Text("Apple ID Password", fontSize = 11.sp) },
                            singleLine = true,
                            visualTransformation = if (showApplePassword) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showApplePassword = !showApplePassword }) {
                                    Icon(
                                        imageVector = if (showApplePassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                        contentDescription = null,
                                        tint = Color(0xFF888888),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, color = Color.White),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CacheTuneOrange,
                                unfocusedBorderColor = Color(0xFF2E2E2E),
                                focusedContainerColor = Color(0xFF0F0F0F),
                                unfocusedContainerColor = Color(0xFF0F0F0F)
                            )
                        )

                        // Storefront & Media-User-Token
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedTextField(
                                value = storefrontInput,
                                onValueChange = { storefrontInput = it },
                                label = { Text("Storefront (e.g. in, us)", fontSize = 11.sp) },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, color = Color.White),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = CacheTuneOrange,
                                    unfocusedBorderColor = Color(0xFF2E2E2E),
                                    focusedContainerColor = Color(0xFF0F0F0F),
                                    unfocusedContainerColor = Color(0xFF0F0F0F)
                                )
                            )
                        }

                        OutlinedTextField(
                            value = appleTokenInput,
                            onValueChange = { appleTokenInput = it },
                            label = { Text("Media-User-Token", fontSize = 11.sp) },
                            singleLine = true,
                            visualTransformation = if (showAppleToken) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showAppleToken = !showAppleToken }) {
                                    Icon(
                                        imageVector = if (showAppleToken) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                        contentDescription = null,
                                        tint = Color(0xFF888888),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, color = Color.White),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CacheTuneOrange,
                                unfocusedBorderColor = Color(0xFF2E2E2E),
                                focusedContainerColor = Color(0xFF0F0F0F),
                                unfocusedContainerColor = Color(0xFF0F0F0F)
                            )
                        )

                        Button(
                            onClick = {
                                onSaveAppleConfig(appleUsernameInput, applePasswordInput, appleTokenInput, storefrontInput, currentWrapperHost)
                                toastMessage = "✔ Apple Music credentials saved locally!"
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CacheTuneOrange),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                        ) {
                            Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("SAVE APPLE CREDENTIALS", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigStatusPill(
    label: String,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isActive) Color(0x1A10B981) else Color(0xFF1E1E1E),
        border = BorderStroke(1.dp, if (isActive) Color(0xFF10B981) else Color(0xFF2E2E2E)),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (isActive) Color(0xFF10B981) else Color(0xFF666666))
            )
            Text(
                text = label,
                color = if (isActive) Color(0xFF34D399) else Color(0xFF888888),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
