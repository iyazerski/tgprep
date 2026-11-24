@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package dev.tgprep

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon

/** Material 3 Expressive in the wallpaper's colors, light or dark as the system is. */
@Composable
fun TgPrepTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = remember(context, dark) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
    MaterialExpressiveTheme(colorScheme = scheme, content = content)
}

/** A [color] filled [shape] behind [content] that morphs with a spring when [shape] changes. */
@Composable
fun MorphingShape(
    shape: RoundedPolygon,
    color: Color,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    var from by remember { mutableStateOf(shape) }
    var to by remember { mutableStateOf(shape) }
    val progress = remember { Animatable(1f) }
    val spec = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    LaunchedEffect(shape) {
        if (shape == to) return@LaunchedEffect
        from = to
        to = shape
        progress.snapTo(0f)
        progress.animateTo(1f, spec)
    }
    val morph = remember(from, to) { Morph(from, to) }
    Box(
        modifier.drawWithCache {
            val path = Path()
            val scale = Matrix().apply { scale(size.width, size.height) }
            onDrawBehind {
                path.rewind()
                // The spring overshoots, and a morph past its ends turns inside out.
                morph.toPath(progress.value.coerceIn(0f, 1f), path)
                path.transform(scale)
                path.translate(size.center - path.getBounds().center)
                drawPath(path, color)
            }
        },
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** A Material Symbols icon from the drawables. */
@Composable
fun Symbol(
    icon: Int,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    description: String? = null,
) {
    Icon(painterResource(icon), contentDescription = description, modifier = modifier, tint = tint)
}
