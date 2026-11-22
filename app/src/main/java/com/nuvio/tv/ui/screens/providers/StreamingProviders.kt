package com.nuvio.tv.ui.screens.providers

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One streaming service. [id] is the raw TMDB `with_watch_providers` value: a single id ("8") or an
 * OR list ("9|119") when TMDB lists the same service under several ids.
 */
@Immutable
data class StreamingProvider(val id: String, val name: String) {
    val ids: List<Int> get() = id.split('|').mapNotNull { it.trim().toIntOrNull() }
}

/** Tile background, mirroring the approved CSS: solid, linear (CSS angle) or elliptical radial. */
sealed interface ProviderBackground {
    data class Solid(val color: Color) : ProviderBackground

    /** linear-gradient([angleDeg]deg, stops...) with stops as (position 0..1, colour). */
    class Linear(val angleDeg: Double, val stops: List<Pair<Float, Color>>) : ProviderBackground

    /**
     * radial-gradient([rxFraction]*width [ryFraction]*height at ([cx]*width, [cy]*height), stops...).
     */
    class Radial(
        val rxFraction: Float,
        val ryFraction: Float,
        val cx: Float,
        val cy: Float,
        val stops: List<Pair<Float, Color>>
    ) : ProviderBackground
}

/**
 * Tile look for one service: [color] is the accent used for the provider page glow, [background] the
 * tile fill, [logoRes] the bundled logo (res/drawable-nodpi/provider_logo_*.png, no network) and
 * [tintWhite] draws it white (ColorFilter.tint SrcIn) when the original logo is dark or single-colour.
 */
@Immutable
data class ProviderBrand(
    val color: Color,
    val background: ProviderBackground,
    @DrawableRes val logoRes: Int?,
    val tintWhite: Boolean
)

private fun solid(hex: Long) = ProviderBackground.Solid(Color(hex))

private fun linear(angleDeg: Double, vararg stops: Pair<Float, Long>) =
    ProviderBackground.Linear(angleDeg, stops.map { it.first to Color(it.second) })

/**
 * Copy of the Discover "Streaming on" list (ui/screens/search/DiscoverFilters.kt,
 * `DiscoverFilterValues.providers`), kept here so the Providers row and page do not depend on the
 * Discover screen. Brand names are not translated.
 */
object CinemaosStreamingProviders {
    val all: List<StreamingProvider> = listOf(
        StreamingProvider("8", "Netflix"),
        StreamingProvider("9|119", "Prime Video"),
        StreamingProvider("337", "Disney+"),
        StreamingProvider("1899", "Max"),
        StreamingProvider("350", "Apple TV+"),
        StreamingProvider("15", "Hulu"),
        StreamingProvider("531", "Paramount+"),
        StreamingProvider("283", "Crunchyroll"),
        StreamingProvider("2336|122", "JioHotstar"),
        StreamingProvider("232", "Zee5"),
        StreamingProvider("237", "SonyLiv")
    )

    /** Tile background + bundled logo per [StreamingProvider.id]. */
    private val brands: Map<String, ProviderBrand> = mapOf(
        "8" to ProviderBrand(Color(0xFFB81D24), solid(0xFF000000), R.drawable.provider_logo_netflix, tintWhite = false),
        "9|119" to ProviderBrand(
            Color(0xFF0A7FD1),
            linear(135.0, 0f to 0xFF1FA6F2, 0.6f to 0xFF0A7FD1, 1f to 0xFF0868B0),
            R.drawable.provider_logo_prime, tintWhite = true
        ),
        "2336|122" to ProviderBrand(
            Color(0xFF3E6BFF),
            linear(110.0, 0f to 0xFF08A8FF, 0.3f to 0xFF3E6BFF, 0.65f to 0xFF9A2BF0, 1f to 0xFFFF1477),
            R.drawable.provider_logo_jiohotstar, tintWhite = true
        ),
        "337" to ProviderBrand(
            Color(0xFF1F5FD6),
            ProviderBackground.Radial(
                1.2f, 1.0f, 0.5f, 1.1f,
                listOf(0f to Color(0xFF1F5FD6), 0.55f to Color(0xFF0B1E5B), 1f to Color(0xFF040B26))
            ),
            R.drawable.provider_logo_disney, tintWhite = false
        ),
        "1899" to ProviderBrand(Color(0xFF4A1A78), solid(0xFF000000), R.drawable.provider_logo_hbo, tintWhite = true),
        "350" to ProviderBrand(Color(0xFF1C1C1E), solid(0xFF000000), R.drawable.provider_logo_appletv, tintWhite = true),
        "15" to ProviderBrand(Color(0xFF1CE783), solid(0xFF0B0C0F), R.drawable.provider_logo_hulu, tintWhite = false),
        "531" to ProviderBrand(
            Color(0xFF0045C4),
            linear(135.0, 0f to 0xFF0B6BFF, 0.55f to 0xFF0045C4, 1f to 0xFF00195A),
            R.drawable.provider_logo_paramount, tintWhite = true
        ),
        "283" to ProviderBrand(
            Color(0xFFF47521),
            linear(135.0, 0f to 0xFFFF8A2B, 0.55f to 0xFFF47521, 1f to 0xFFC9520E),
            R.drawable.provider_logo_crunchyroll, tintWhite = true
        ),
        "232" to ProviderBrand(
            Color(0xFF2A0B45),
            linear(135.0, 0f to 0xFF2A0B45, 1f to 0xFF14052A),
            R.drawable.provider_logo_zee5, tintWhite = false
        ),
        "237" to ProviderBrand(Color(0xFF1A123C), solid(0xFF000000), R.drawable.provider_logo_sonyliv, tintWhite = false)
    )

    private val fallbackBrand = ProviderBrand(Color(0xFF1C1C1E), solid(0xFF1C1C1E), null, tintWhite = false)

    fun brandFor(id: String): ProviderBrand = brands[id] ?: fallbackBrand

    fun byId(id: String): StreamingProvider? = all.firstOrNull { it.id == id }

    /** Region used for `watch_region`: the device country, else US. */
    fun watchRegion(): String = Locale.getDefault().country.takeIf { it.length == 2 }?.uppercase() ?: "US"
}

/** Provider logos from TMDB's global provider lists, fetched once per process. */
@Singleton
class ProviderLogoRepository @Inject constructor(
    private val api: CinemaosTmdbExtrasApi
) {
    private val mutex = Mutex()
    @Volatile private var cache: Map<String, String>? = null

    /** Provider [StreamingProvider.id] -> logo URL (w185). Missing entries simply show the name. */
    suspend fun logos(): Map<String, String> {
        cache?.let { return it }
        return mutex.withLock {
            cache?.let { return@withLock it }
            val loaded = load()
            if (loaded.isNotEmpty()) cache = loaded
            loaded
        }
    }

    private suspend fun load(): Map<String, String> = withContext(Dispatchers.IO) {
        val key = BuildConfig.TMDB_API_KEY
        if (key.isBlank()) return@withContext emptyMap()
        val entries = coroutineScope {
            val movies = async { runCatching { api.movieWatchProviders(key).body()?.results }.getOrNull().orEmpty() }
            val tv = async { runCatching { api.tvWatchProviders(key).body()?.results }.getOrNull().orEmpty() }
            movies.await() + tv.await()
        }
        val logoById = HashMap<Int, String>()
        entries.forEach { entry ->
            val url = tmdbImage(entry.logoPath, "w185") ?: return@forEach
            logoById.putIfAbsent(entry.providerId, url)
        }
        CinemaosStreamingProviders.all.mapNotNull { provider ->
            provider.ids.firstNotNullOfOrNull { logoById[it] }?.let { provider.id to it }
        }.toMap()
    }
}
