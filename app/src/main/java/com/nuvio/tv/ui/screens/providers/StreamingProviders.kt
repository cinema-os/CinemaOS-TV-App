package com.nuvio.tv.ui.screens.providers

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.nuvio.tv.BuildConfig
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

/**
 * Tile look for one service: [color] is the gradient's brand colour, [wordmarkUrl] a rectangular
 * wordmark (Wikimedia Commons PNG render of the official SVG/PNG, 500px), and [tintWhite] draws it
 * white when the original logo is dark or the brand's own colour.
 */
@Immutable
data class ProviderBrand(val color: Color, val wordmarkUrl: String, val tintWhite: Boolean)

private const val COMMONS = "https://upload.wikimedia.org/wikipedia/commons/thumb/"

private fun brand(r: Int, g: Int, b: Int, path: String, tintWhite: Boolean) =
    ProviderBrand(Color(r, g, b), COMMONS + path, tintWhite)

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

    /** Brand colour + wordmark per [StreamingProvider.id]. See provider-logos notes for sources. */
    private val brands: Map<String, ProviderBrand> = mapOf(
        "8" to brand(184, 29, 36, "0/08/Netflix_2015_logo.svg/500px-Netflix_2015_logo.svg.png", tintWhite = true),
        "9|119" to brand(15, 61, 92, "9/90/Prime_Video_logo_(2024).svg/500px-Prime_Video_logo_(2024).svg.png", tintWhite = true),
        "337" to brand(26, 77, 184, "7/77/Disney_Plus_logo.svg/500px-Disney_Plus_logo.svg.png", tintWhite = false),
        "1899" to brand(74, 26, 120, "7/7e/HBO_Max_May_2025_(Horizontal).svg/500px-HBO_Max_May_2025_(Horizontal).svg.png", tintWhite = true),
        "350" to brand(28, 28, 30, "2/28/Apple_TV_Plus_Logo.svg/500px-Apple_TV_Plus_Logo.svg.png", tintWhite = true),
        "15" to brand(12, 61, 44, "f/f9/Hulu_logo_(2018).svg/500px-Hulu_logo_(2018).svg.png", tintWhite = false),
        "531" to brand(10, 58, 122, "4/4e/Paramount+_logo.svg/500px-Paramount+_logo.svg.png", tintWhite = true),
        "283" to brand(122, 52, 16, "d/d9/Crunchyroll_2024.svg/500px-Crunchyroll_2024.svg.png", tintWhite = true),
        "2336|122" to brand(36, 20, 82, "4/40/JioHotstar_2025.png/500px-JioHotstar_2025.png", tintWhite = true),
        "232" to brand(70, 20, 88, "6/6e/ZEE5_2025.svg/500px-ZEE5_2025.svg.png", tintWhite = false),
        // SonyLIV: Commons only has the square app icon, no wordmark.
        "237" to brand(26, 18, 60, "f/f7/SonyLIV_2020.png/500px-SonyLIV_2020.png", tintWhite = false)
    )

    private val fallbackBrand = ProviderBrand(Color(0xFF1C1C1E), "", tintWhite = false)

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
