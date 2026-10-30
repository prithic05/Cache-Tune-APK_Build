package com.cachetune.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.cachetune.app.ui.theme.SurfaceBorder
import com.cachetune.app.ui.theme.SurfaceGlass

/**
 * Aesthetic Glassmorphic Container.
 * Provides frosted glass aesthetics with translucent layering and neon border accents.
 */
@Composable
fun GlassmorphicCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    backgroundColor: Color = SurfaceGlass,
    borderColor: Color = SurfaceBorder,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            color = backgroundColor,
            border = BorderStroke(1.dp, borderColor)
        ) {
            content()
        }
    } else {
        Surface(
            modifier = modifier,
            shape = shape,
            color = backgroundColor,
            border = BorderStroke(1.dp, borderColor)
        ) {
            content()
        }
    }
}
