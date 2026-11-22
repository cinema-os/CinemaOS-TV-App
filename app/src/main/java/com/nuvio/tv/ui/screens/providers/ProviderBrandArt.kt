package com.nuvio.tv.ui.screens.providers

import androidx.compose.foundation.Image
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
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
 * Streaming provider tile artwork (not focusable): a 16:9 rounded tile painted with the provider's
 * own background (solid / linear / radial, see [ProviderBrand]) plus a faint white radial highlight,
 * with the bundled logo centred on top (max 78% x 52%, Fit, soft shadow). Used by the Home Providers
 * row ([ProviderLogoTile]) and the Provider page header. There is no grey container behind it.
 *
 * The logo is a bundled drawable (no network). Providers without one fall back to [tmdbLogoUrl] (the
 * square TMDB provider icon) and finally the provider name.
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
    val logoRes = brand.logoRes
    var tmdbFailed by remember(tmdbLogoUrl) { mutableStateOf(false) }

    Box(
        modifier = modifier
            .clip(shape)
            .drawBehind { drawBrandBackground(brand.background) },
        contentAlignment = Alignment.Center
    ) {
        when {
            logoRes != null -> {
                // Max 78% x 52% of the tile, Fit, so any logo aspect ratio stays centred.
                val box = Modifier.fillMaxWidth(0.78f).fillMaxHeight(0.52f)
                // Soft drop shadow (black 45%): a blurred, offset black copy of the same logo.
                Image(
                    painter = painterResource(logoRes),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    colorFilter = ColorFilter.tint(Color.Black.copy(alpha = 0.45f), BlendMode.SrcIn),
                    modifier = box
                        .offset(y = 3.dp)
                        .blur(5.dp, BlurredEdgeTreatment.Unbounded)
                )
                Image(
                    painter = painterResource(logoRes),
                    contentDescription = provider.name,
                    contentScale = ContentScale.Fit,
                    colorFilter = if (brand.tintWhite) ColorFilter.tint(Color.White, BlendMode.SrcIn) else null,
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

/** Fills the tile with [background], then the faint white highlight common to every tile. */
private fun DrawScope.drawBrandBackground(background: ProviderBackground) {
    when (background) {
        is ProviderBackground.Solid -> drawRect(background.color)
        is ProviderBackground.Linear -> {
            // CSS angles: 0deg points up, 90deg right; the gradient line passes through the centre and
            // its length is |w*sin| + |h*cos| so the corners get exactly the first/last colour.
            val angle = background.angleDeg * PI / 180.0
            val dx = sin(angle).toFloat()
            val dy = -cos(angle).toFloat()
            val length = abs(size.width * dx) + abs(size.height * dy)
            val half = Offset(dx * length / 2f, dy * length / 2f)
            drawRect(
                brush = Brush.linearGradient(
                    *background.stops.toTypedArray(),
                    start = center - half,
                    end = center + half
                )
            )
        }
        is ProviderBackground.Radial -> drawEllipse(
            center = Offset(size.width * background.cx, size.height * background.cy),
            rx = size.width * background.rxFraction,
            ry = size.height * background.ryFraction,
            stops = background.stops
        )
    }

    // radial-gradient(120% 80% at 80% -10%, rgba(255,255,255,.10), transparent 55%)
    drawEllipse(
        center = Offset(size.width * 0.8f, -size.height * 0.1f),
        rx = size.width * 1.2f,
        ry = size.height * 0.8f,
        stops = listOf(0f to Color.White.copy(alpha = 0.10f), 0.55f to Color.White.copy(alpha = 0f))
    )
}

/**
 * Elliptical radial gradient (radii [rx], [ry], colour [stops] at 0..1 along the radius). Compose only
 * has circular radial gradients, so draw a circle of radius [rx] squashed vertically by ry/rx.
 */
private fun DrawScope.drawEllipse(center: Offset, rx: Float, ry: Float, stops: List<Pair<Float, Color>>) {
    withTransform({ scale(scaleX = 1f, scaleY = ry / rx, pivot = center) }) {
        drawCircle(
            brush = Brush.radialGradient(
                *stops.toTypedArray(),
                center = center,
                radius = rx
            ),
            radius = rx,
            center = center
        )
    }
}
