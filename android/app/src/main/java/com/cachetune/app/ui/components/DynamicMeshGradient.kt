package com.cachetune.app.ui.components

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.cachetune.app.ui.theme.BackgroundDark
import com.cachetune.app.ui.theme.ElectricBlue
import com.cachetune.app.ui.theme.VelvetPurple

/**
 * Dynamic Ambient Mesh Gradient.
 * 
 * Extracts vibrant and dominant colors from the album cover in real-time
 * and smoothly animates the backdrop, creating an Apple Music-style living background.
 */
@Composable
fun DynamicMeshGradient(
    artworkUrl: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    var dominantColor: Color by remember { mutableStateOf(VelvetPurple) }
    var secondaryColor: Color by remember { mutableStateOf(ElectricBlue) }

    LaunchedEffect(artworkUrl) {
        if (artworkUrl.isNotBlank()) {
            val loader = ImageLoader(context)
            val request = ImageRequest.Builder(context)
                .data(artworkUrl)
                .allowHardware(false) // Required for Palette software bitmap extraction
                .build()

            val result = loader.execute(request)
            if (result is SuccessResult) {
                val bitmap = (result.drawable as? BitmapDrawable)?.bitmap
                if (bitmap != null) {
                    Palette.from(bitmap).generate { palette ->
                        palette?.dominantSwatch?.rgb?.let { dominantColor = Color(it) }
                        palette?.vibrantSwatch?.rgb?.let { secondaryColor = Color(it) }
                    }
                }
            }
        }
    }

    // Smooth 1000ms color morphing between tracks
    val animatedDominant by animateColorAsState(
        targetValue = dominantColor.copy(alpha = 0.35f),
        animationSpec = tween(durationMillis = 1000),
        label = "dominant_color"
    )

    val animatedSecondary by animateColorAsState(
        targetValue = secondaryColor.copy(alpha = 0.25f),
        animationSpec = tween(durationMillis = 1000),
        label = "secondary_color"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .background(
                Brush.radialGradient(
                    colors = listOf(animatedDominant, Color.Transparent),
                    center = Offset(200f, 300f),
                    radius = 900f
                )
            )
            .background(
                Brush.radialGradient(
                    colors = listOf(animatedSecondary, Color.Transparent),
                    center = Offset(800f, 1400f),
                    radius = 1100f
                )
            )
    ) {
        content()
    }
}
