package com.nuvio.tv.ui.screens.calendar

import android.util.Log
import androidx.compose.runtime.Immutable
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbDiscoverResponse
import com.nuvio.tv.data.remote.api.TmdbDiscoverResult
import com.nuvio.tv.data.remote.api.TmdbEpisode
import com.nuvio.tv.ui.screens.providers.CinemaosMovieReleaseDetails
import com.nuvio.tv.ui.screens.providers.CinemaosTmdbExtrasApi
import com.nuvio.tv.ui.screens.providers.CinemaosTvAiringDetails
import com.nuvio.tv.ui.screens.providers.tmdbImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import retrofit2.Response
import java.time.LocalDate
import java.time.YearMonth
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** A title the calendar follows: from the library / watchlist or from Continue Watching. */
@Immutable
data class CalendarSource(
    val id: String,
    val isSeries: Boolean,
    val name: String,
    val poster: String?,
    val addonBaseUrl: String?,
    val tmdbId: Int?,
    val imdbId: String?
)

/** One line on a calendar day: a movie release, or one or more episodes of a series. */
@Immutable
data class CalendarEntry(
    val key: String,
    val itemId: String,
    val itemType: String,
    val addonBaseUrl: String?,
    val title: String,
    val poster: String?,
    val date: LocalDate,
    val isMovie: Boolean,
    val season: Int? = null,
    val firstEpisode: Int? = null,
    val lastEpisode: Int? = null,
    val episodeCount: Int = 0,
    val episodeTitle: String? = null,
    val tmdbId: Int? = null,
    /** True for titles from the user's library / Continue Watching; they sort first within a day. */
    val fromLibrary: Boolean = false
)

/**
 * Builds a month of [CalendarEntry]s from TMDB: for series, the episode `air_date`s of the current
 * and next season (`tv/{id}` -> `tv/{id}/season/{n}`); for movies, `release_date`. Uses the shared
 * Retrofit TMDB client (Hilt OkHttpClient with DNS over HTTPS) and the app's TMDB key. Results are
 * cached per month and per title, and at most [MAX_CONCURRENT] titles are fetched at once.
 */
@Singleton
class CalendarRepository @Inject constructor(
    private val tmdbApi: TmdbApi,
    private val extrasApi: CinemaosTmdbExtrasApi,
    private val tmdbService: TmdbService
) {
    private data class Timed<T>(val value: T, val at: Long)

    private val monthCache = ConcurrentHashMap<String, Timed<List<CalendarEntry>>>()
    private val tvCache = ConcurrentHashMap<Int, Timed<CinemaosTvAiringDetails?>>()
    private val seasonCache = ConcurrentHashMap<String, Timed<List<TmdbEpisode>>>()
    private val movieCache = ConcurrentHashMap<Int, Timed<CinemaosMovieReleaseDetails?>>()

    val isAvailable: Boolean get() = BuildConfig.TMDB_API_KEY.isNotBlank()

    fun cached(month: YearMonth, sources: List<CalendarSource>): List<CalendarEntry>? =
        monthCache[cacheKey(month, sources)]?.takeIf { fresh(it.at, MONTH_TTL_MS) }?.value

    suspend fun loadMonth(month: YearMonth, sources: List<CalendarSource>): List<CalendarEntry> {
        if (!isAvailable) return emptyList()
        val key = cacheKey(month, sources)
        monthCache[key]?.takeIf { fresh(it.at, MONTH_TTL_MS) }?.let { return it.value }
        val entries = withContext(Dispatchers.IO) {
            val gate = Semaphore(MAX_CONCURRENT)
            coroutineScope {
                val discoverJob = async { discoverEntries(month, gate) }
                val library = sources.map { source ->
                    async {
                        gate.withPermit {
                            guarded("library ${source.id}") {
                                if (source.isSeries) seriesEntries(source, month) else movieEntries(source, month)
                            }.map { it.copy(fromLibrary = true) }
                        }
                    }
                }.awaitAll().flatten()
                val owned = library.mapNotNull { e -> e.tmdbId?.let { "${e.isMovie}:$it" } }.toHashSet()
                val discovered = discoverJob.await().filter { e -> "${e.isMovie}:${e.tmdbId}" !in owned }
                library + discovered
            }
        }.sortedWith(
            compareBy<CalendarEntry> { it.date }
                .thenBy { !it.fromLibrary }
                .thenBy { it.isMovie }
                .thenBy { it.title.lowercase() }
        )
        monthCache[key] = Timed(entries, System.currentTimeMillis())
        return entries
    }

    private suspend fun <T> guarded(label: String, fallback: List<T> = emptyList(), block: suspend () -> List<T>): List<T> =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Calendar load failed: $label", e)
            fallback
        }

    private suspend fun results(
        label: String,
        call: suspend () -> Response<TmdbDiscoverResponse>
    ): List<TmdbDiscoverResult> = guarded(label) {
        val response = call()
        if (!response.isSuccessful) {
            Log.w(TAG, "Calendar $label HTTP ${response.code()}")
            emptyList()
        } else {
            response.body()?.results.orEmpty()
        }
    }

    /** Like cinejoy.pk/calendar: TMDB discover for the month's airing shows and releasing movies. */
    private suspend fun discoverEntries(month: YearMonth, gate: Semaphore): List<CalendarEntry> = coroutineScope {
        val start = month.atDay(1).toString()
        val end = month.atEndOfMonth().toString()
        val apiKey = BuildConfig.TMDB_API_KEY
        val tvAired = listOf(1, 2).map { page ->
            async {
                results("discover tv air_date p$page") {
                    extrasApi.discoverTvByAirDate(apiKey, airDateGte = start, airDateLte = end, page = page)
                }
            }
        }
        val tvPremieres = async {
            results("discover tv first_air_date") {
                tmdbApi.discoverTv(
                    apiKey = apiKey,
                    page = 1,
                    sortBy = "popularity.desc",
                    firstAirDateGte = start,
                    firstAirDateLte = end,
                    includeAdult = false
                )
            }
        }
        val movies = listOf(1, 2).map { page ->
            async {
                results("discover movie p$page") {
                    tmdbApi.discoverMovies(
                        apiKey = apiKey,
                        page = page,
                        sortBy = "popularity.desc",
                        releaseDateGte = start,
                        releaseDateLte = end,
                        includeAdult = false
                    )
                }
            }
        }
        val shows = (tvAired.awaitAll().flatten() + tvPremieres.await())
            .distinctBy { it.id }
            .take(MAX_DISCOVER_SHOWS)
        val showEntries = shows.map { show ->
            async {
                gate.withPermit {
                    guarded("tv ${show.id}") {
                        seriesEntries(
                            CalendarSource(
                                id = "tmdb:${show.id}",
                                isSeries = true,
                                name = show.name?.takeIf { it.isNotBlank() }
                                    ?: show.title?.takeIf { it.isNotBlank() }
                                    ?: show.originalName.orEmpty(),
                                poster = tmdbImage(show.posterPath, "w342") ?: tmdbImage(show.backdropPath, "w342"),
                                addonBaseUrl = null,
                                tmdbId = show.id,
                                imdbId = null
                            ),
                            month
                        )
                    }
                }
            }
        }.awaitAll().flatten()

        val movieEntries = movies.awaitAll().flatten().distinctBy { it.id }.mapNotNull { movie ->
            val date = movie.releaseDate?.let(::parseDate) ?: return@mapNotNull null
            if (YearMonth.from(date) != month) return@mapNotNull null
            val title = movie.title?.takeIf { it.isNotBlank() }
                ?: movie.originalTitle?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            CalendarEntry(
                key = "tmdb:${movie.id}|movie|$date",
                itemId = "tmdb:${movie.id}",
                itemType = "movie",
                addonBaseUrl = null,
                title = title,
                poster = tmdbImage(movie.posterPath, "w342") ?: tmdbImage(movie.backdropPath, "w342"),
                date = date,
                isMovie = true,
                tmdbId = movie.id
            )
        }
        showEntries + movieEntries
    }

    private suspend fun resolveTmdbId(source: CalendarSource): Int? {
        source.tmdbId?.let { return it }
        val type = if (source.isSeries) "series" else "movie"
        return runCatching { tmdbService.ensureTmdbId(source.id, type, source.imdbId) }
            .getOrNull()?.toIntOrNull()
    }

    private suspend fun seriesEntries(source: CalendarSource, month: YearMonth): List<CalendarEntry> {
        val tmdbId = resolveTmdbId(source) ?: return emptyList()
        val details = tvDetails(tmdbId) ?: return emptyList()
        val monthStart = month.atDay(1)
        val monthEnd = month.atEndOfMonth()

        val lastAired = details.lastAirDate?.let(::parseDate)
        val next = details.nextEpisodeToAir
        // Nothing upcoming and nothing aired since this month began: the show cannot appear here.
        if (next == null && (lastAired == null || lastAired.isBefore(monthStart))) return emptyList()

        val seasons = listOfNotNull(details.lastEpisodeToAir?.seasonNumber, next?.seasonNumber)
            .filter { it > 0 }
            .distinct()
        if (seasons.isEmpty()) return emptyList()

        val poster = source.poster?.takeIf { it.isNotBlank() } ?: tmdbImage(details.posterPath, "w342")
        val title = source.name.ifBlank { details.name.orEmpty() }
        val result = ArrayList<CalendarEntry>()
        seasons.forEach { seasonNumber ->
            val episodes = seasonEpisodes(tmdbId, seasonNumber)
            episodes
                .mapNotNull { ep ->
                    val date = ep.airDate?.let(::parseDate) ?: return@mapNotNull null
                    val number = ep.episodeNumber ?: return@mapNotNull null
                    if (date.isBefore(monthStart) || date.isAfter(monthEnd)) null else Triple(date, number, ep.name)
                }
                .groupBy { it.first }
                .forEach { (date, onDay) ->
                    val sorted = onDay.sortedBy { it.second }
                    result += CalendarEntry(
                        key = "${source.id}|s$seasonNumber|$date",
                        itemId = source.id,
                        itemType = "series",
                        addonBaseUrl = source.addonBaseUrl,
                        title = title,
                        poster = poster,
                        date = date,
                        isMovie = false,
                        season = seasonNumber,
                        firstEpisode = sorted.first().second,
                        lastEpisode = sorted.last().second,
                        episodeCount = sorted.size,
                        episodeTitle = sorted.singleOrNull()?.third?.takeIf { it.isNotBlank() },
                        tmdbId = tmdbId
                    )
                }
        }
        return result
    }

    private suspend fun movieEntries(source: CalendarSource, month: YearMonth): List<CalendarEntry> {
        val tmdbId = resolveTmdbId(source) ?: return emptyList()
        val details = movieDetails(tmdbId) ?: return emptyList()
        val date = details.releaseDate?.let(::parseDate) ?: return emptyList()
        if (YearMonth.from(date) != month) return emptyList()
        return listOf(
            CalendarEntry(
                key = "${source.id}|movie|$date",
                itemId = source.id,
                itemType = "movie",
                addonBaseUrl = source.addonBaseUrl,
                title = source.name.ifBlank { details.title.orEmpty() },
                poster = source.poster?.takeIf { it.isNotBlank() } ?: tmdbImage(details.posterPath, "w342"),
                date = date,
                isMovie = true,
                tmdbId = tmdbId
            )
        )
    }

    private suspend fun tvDetails(tmdbId: Int): CinemaosTvAiringDetails? {
        tvCache[tmdbId]?.takeIf { fresh(it.at, TITLE_TTL_MS) }?.let { return it.value }
        val response = extrasApi.tvAiringDetails(tmdbId, BuildConfig.TMDB_API_KEY)
        if (!response.isSuccessful) {
            Log.w(TAG, "tv/$tmdbId HTTP ${response.code()}")
            return null
        }
        val body = response.body()
        tvCache[tmdbId] = Timed(body, System.currentTimeMillis())
        return body
    }

    private suspend fun seasonEpisodes(tmdbId: Int, season: Int): List<TmdbEpisode> {
        val key = "$tmdbId:$season"
        seasonCache[key]?.takeIf { fresh(it.at, TITLE_TTL_MS) }?.let { return it.value }
        val response = runCatching { tmdbApi.getTvSeasonDetails(tmdbId, season, BuildConfig.TMDB_API_KEY) }.getOrNull()
        val episodes = response?.takeIf { it.isSuccessful }?.body()?.episodes.orEmpty()
        if (response?.isSuccessful == true) seasonCache[key] = Timed(episodes, System.currentTimeMillis())
        return episodes
    }

    private suspend fun movieDetails(tmdbId: Int): CinemaosMovieReleaseDetails? {
        movieCache[tmdbId]?.takeIf { fresh(it.at, TITLE_TTL_MS) }?.let { return it.value }
        val response = extrasApi.movieReleaseDetails(tmdbId, BuildConfig.TMDB_API_KEY)
        if (!response.isSuccessful) {
            Log.w(TAG, "movie/$tmdbId HTTP ${response.code()}")
            return null
        }
        val body = response.body()
        movieCache[tmdbId] = Timed(body, System.currentTimeMillis())
        return body
    }

    private fun cacheKey(month: YearMonth, sources: List<CalendarSource>): String =
        "$month|${sources.map { it.id }.sorted().hashCode()}"

    private fun fresh(at: Long, ttl: Long) = System.currentTimeMillis() - at < ttl

    private fun parseDate(raw: String): LocalDate? =
        runCatching { LocalDate.parse(raw.take(10)) }.getOrNull()

    private companion object {
        const val TAG = "CalendarRepository"
        const val MAX_DISCOVER_SHOWS = 40
        const val MAX_CONCURRENT = 4
        const val MONTH_TTL_MS = 30 * 60_000L
        const val TITLE_TTL_MS = 6 * 60 * 60_000L
    }
}
