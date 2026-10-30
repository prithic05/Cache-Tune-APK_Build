package com.cachetune.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Pure Audio Focus Action Pills Row.
 * 1. Lyrics: interactive synchronized lyrics sheet.
 * 2. Dynamic Audio Quality Badge: dynamically analysed by Python (e.g. "2296 kbps • 96.0 kHz"). Never static "FLAC 24-bit".
 * 3. Real-Time Processing Status: replaces Share with live state (Downloading..., Decrypting..., Converting..., Ready).
 */
@Composable
fun ActionPillRow(
    onLyricsClick: () -> Unit,
    onDownloadFlacClick: () -> Unit,
    qualityLabel: String = "2296 kbps • 96.0 kHz",
    statusPhase: String = "Ready",
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. Lyrics Pill
        PillButton(
            icon = Icons.Rounded.ShortText,
            label = "Lyrics",
            onClick = onLyricsClick
        )

        // 2. Dynamic Bitrate & kHz Audio Quality Badge (Analysed by Python backend)
        PillButton(
            icon = Icons.Rounded.GraphicEq,
            label = qualityLabel,
            onClick = onDownloadFlacClick,
            highlight = true
        )

        // 3. Real-Time Processing Status Pill (Replaces Share button)
        val cleanPhase = statusPhase.lowercase().trim()
        val (statusIcon, statusLabel, isHighlight) = when {
            cleanPhase.contains("download") -> Triple(Icons.Rounded.CloudDownload, "Downloading...", true)
            cleanPhase.contains("decrypt") -> Triple(Icons.Rounded.LockOpen, "Decrypting...", true)
            cleanPhase.contains("convert") -> Triple(Icons.Rounded.Sync, "Converting...", true)
            cleanPhase.contains("resolv") -> Triple(Icons.Rounded.HourglassTop, "Resolving...", true)
            else -> Triple(Icons.Rounded.CheckCircle, "Ready", false)
        }

        PillButton(
            icon = statusIcon,
            label = statusLabel,
            onClick = {},
            highlight = isHighlight
        )
    }
}

@Composable
private fun PillButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    highlight: Boolean = false
) {
    Row(
        modifier = Modifier
            .height(38.dp)
            .clip(RoundedCornerShape(19.dp))
            .background(if (highlight) Color(0xFF332A4A) else Color(0xFF242424))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (highlight) Color(0xFFC084FC) else Color.White,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = label,
            color = if (highlight) Color(0xFFC084FC) else Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
