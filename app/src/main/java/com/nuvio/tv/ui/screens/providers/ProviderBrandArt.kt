package com.nuvio.tv.ui.screens.providers

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Streaming provider tile artwork (not focusable): a 16:9 rounded tile painted with the brand
 * gradient plus a soft radial highlight, and the provider's rectangular wordmark centred on top.
 * Used by the Home Providers row ([ProviderLogoTile]) and the Provider page header. There is no grey
 * container behind it; the gradient is the whole background.
 *
 * Wordmark comes from [CinemaosStreamingProviders.brandFor]; if it fails to load, [tmdbLogoUrl] (the
 * square TMDB provider icon) is shown instead, and finally the provider name.
 */
@Composable
internal fun ProviderBrandArt(
    provider: StreamingProvider,
    tmdbLogoUrl: String?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp
) {
    val brand = CinemaosStreamingProviders.brandFor(provider.id)
    val shape = RoundedCornerShape(cornerRadius)
    // 58% brand + 42% #050505.
    val gradientEnd = remember(brand.color) { lerp(Color(0xFF050505), brand.color, 0.58f) }

    var wordmarkFailed by remember(brand.wordmarkUrl) { mutableStateOf(brand.wordmarkUrl.isBlank()) }
    var tmdbFailed by remember(tmdbLogoUrl) { mutableStateOf(false) }

    Box(
        modifier = modifier
            .clip(shape)
            .drawBehind { drawBrandBackground(brand.color, gradientEnd) },
        contentAlignment = Alignment.Center
    ) {
        when {
            !wordmarkFailed -> {
                // Max 78% x 52% of the tile, Fit, so any wordmark aspect ratio stays centred.
                val box = Modifier.fillMaxWidth(0.78f).fillMaxHeight(0.52f)
                // Drop shadow (black 45%): a blurred, offset black copy of the same wordmark.
                AsyncImage(
                    model = brand.wordmarkUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    colorFilter = ColorFilter.tint(Color.Black.copy(alpha = 0.45f), BlendMode.SrcIn),
                    modifier = box
                        .offset(y = 1.5.dp)
                        .blur(2.dp, BlurredEdgeTreatment.Unbounded)
                )
                AsyncImage(
                    model = brand.wordmarkUrl,
                    contentDescription = provider.name,
                    contentScale = ContentScale.Fit,
                    colorFilter = if (brand.tintWhite) ColorFilter.tint(Color.White, BlendMode.SrcIn) else null,
                    onError = { wordmarkFailed = true },
                    modifier = box
                )
            }
            tmdbLogoUrl != null && !tmdbFailed -> AsyncImage(
                model = tmdbLogoUrl,
                contentDescription = provider.name,
                contentScale = ContentScale.Fit,
                onError = { tmdbFailed = true },
                modifier = Modifier.fillMaxWidth(0.78f).fillMaxHeight(0.7f)
            )
            else -> Text(
                text = provider.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 10.dp)
            )
        }
    }
}

/**
 * linear-gradient(145deg, [start], [end]) plus radial-gradient(ellipse at 80% -10%,
 * rgba(255,255,255,.18), transparent 55%), as in the web reference.
 */
private fun DrawScope.drawBrandBackground(start: Color, end: Color) {
    // CSS angles: 0deg points up, 90deg right; the gradient line passes through the centre and its
    // length is |w*sin| + |h*cos| so the corners get exactly the first/last colour.
    val angle = 145.0 * PI / 180.0
    val dx = sin(angle).toFloat()
    val dy = -cos(angle).toFloat()
    val length = abs(size.width * dx) + abs(size.height * dy)
    val half = Offset(dx * length / 2f, dy * length / 2f)
    drawRect(
        brush = Brush.linearGradient(
            colors = listOf(start, end),
            start = center - half,
            end = center + half
        )
    )

    // Farthest-corner ellipse centred at (80%, -10%): radii are the farthest-side distances
    // (0.8w horizontally, 1.1h vertically) times sqrt(2). Compose only has circular radial
    // gradients, so draw a circle of radius rx squashed vertically by ry/rx.
    val highlightCenter = Offset(size.width * 0.8f, -size.height * 0.1f)
    val rx = size.width * 0.8f * 1.4142135f
    val ry = size.height * 1.1f * 1.4142135f
    withTransform({ scale(scaleX = 1f, scaleY = ry / rx, pivot = highlightCenter) }) {
        drawCircle(
            brush = Brush.radialGradient(
                0f to Color.White.copy(alpha = 0.18f),
                0.55f to Color.White.copy(alpha = 0f),
                center = highlightCenter,
                radius = rx
            ),
            radius = rx,
            center = highlightCenter
        )
    }
}
