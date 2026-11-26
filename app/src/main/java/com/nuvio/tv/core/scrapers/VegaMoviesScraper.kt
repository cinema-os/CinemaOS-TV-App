package com.nuvio.tv.core.scrapers

import com.nuvio.tv.domain.model.LocalScraperResult
import java.net.URLEncoder

/**
 * VegaMovies: typesense-style `/search.php?q=` JSON -> post -> NexDrive page (`genxfm` ids) -> V-Cloud (FSLv2 R2 /
 * 10Gbps / PixelDrain) for movies; series episodes only offer fastdl.beer G-Direct (Google URL).
 */
class VegaMoviesScraper(private val http: CinemaHttp) : CinemaScraper {
    override val id = "cinemaos-vegamovies"
    override val name = "CinemaOS · VegaMovies"
    override val description = "VegaMovies V-Cloud / G-Direct MKV links (Hindi + English)"
    override val budgetMs = 26_000L

    private val resolver = HubResolver(http)
    private val domains = SiteDomains(http)

    private class Rel(val label: String, val href: String) {
        val quality = qualityNumber(label)
    }

    override suspend fun getStreams(req: CinemaScrapeRequest): List<LocalScraperResult> {
        if (!Regex("^\\d+$").matches(req.tmdbId)) return emptyList()
        val brief = http.tmdbBrief(if (req.isMovie) "movie" else "tv", req.tmdbId)
        if (brief.title.isBlank()) return emptyList()
        val headers = mapOf("User-Agent" to CinemaHttp.SAFARI_UA, "Accept" to "text/html,application/json,*/*;q=0.8")

        for (base in domains.candidates("vegamovies", "https://vegamovies.latino").take(2)) {
            val post = search(base, brief, req, headers)
            if (post == null) continue
            val html = http.fetch(post, headers, timeoutMs = 8_000, maxBytes = 3 * 1024 * 1024)?.takeIf { it.ok }?.text
            if (html == null) { domains.markDead(base); continue }
            val rels = parseReleases(html, req)
            if (rels.isEmpty()) continue
            val chosen = rels.sortedBy { qualityRank(it.quality) }.take(MAX_RELEASES)
            val cands = ArrayList<HubCand>()
            parallelUntil<Rel, List<HubCand>>(chosen, timeoutMs = 16_000) { rel ->
                val links = nexdrive(rel, req, headers)
                links.map { HubCand(it, it.file ?: rel.label, rel.quality.takeIf { q -> q > 0 } ?: qualityNumber(it.file.orEmpty()), it.size ?: sizeOf(rel.label), rel.label) }
                    .takeIf { it.isNotEmpty() }
            }.forEach { cands += it }
            return http.verifyAndMap(cands, "VegaMovies")
        }
        return emptyList()
    }

    private suspend fun search(base: String, brief: TmdbBrief, req: CinemaScrapeRequest, headers: Map<String, String>): String? {
        // IMDb search is unreliable (some posts have no imdb_id), so the title search always follows.
        for (term in listOfNotNull(brief.imdbId.takeIf { it.isNotBlank() }, brief.title)) {
            val r = http.fetch("$base/search.php?q=${URLEncoder.encode(term, "UTF-8")}", headers, timeoutMs = 6_000, maxBytes = 1024 * 1024)
                ?: run { domains.markDead(base); return null }
            if (!r.ok) continue
            val hits = runCatching { org.json.JSONObject(r.text).optJSONArray("hits") }.getOrNull() ?: continue
            for (i in 0 until hits.length()) {
                val d = hits.optJSONObject(i)?.optJSONObject("document") ?: continue
                val title = d.str("post_title") ?: continue
                val link = d.str("permalink") ?: continue
                val imdbOk = brief.imdbId.isNotBlank() && d.str("imdb_id") == brief.imdbId
                val isSeries = Regex("season|web series|episode", RegexOption.IGNORE_CASE).containsMatchIn(title)
                if (req.isMovie) {
                    if (isSeries && !imdbOk) continue
                    if (imdbOk || titleMatches(title, brief.title, brief.year, true)) return abs(base, link)
                } else {
                    if (!isSeries || !titleMatches(title, brief.title, "", false)) continue
                    if (!Regex("single|episode|season\\s*\\d", RegexOption.IGNORE_CASE).containsMatchIn(title)) continue
                    return abs(base, link)
                }
            }
        }
        return null
    }

    private fun abs(base: String, link: String) = if (link.startsWith("http")) link else base + (if (link.startsWith("/")) link else "/$link")

    /** Heading text followed by a nexdrive / genxfm "Download Now" anchor; series: only the requested season. */
    private fun parseReleases(html: String, req: CinemaScrapeRequest): List<Rel> {
        val out = ArrayList<Rel>()
        val rx = Regex("""<h[2-5][^>]*>(.*?)</h[2-5]>\s*(?:<p[^>]*>)?\s*<a\s[^>]*href=["']([^"']*(?:nexdrive|genxfm)[^"']*)["']""",
            RegexOption.DOT_MATCHES_ALL)
        for (m in rx.findAll(html)) {
            val label = m.groupValues[1].stripTags()
            if (!req.isMovie) {
                val s = Regex("season\\s*0*(\\d+)", RegexOption.IGNORE_CASE).find(label)?.groupValues?.get(1)?.toIntOrNull()
                if (s != req.season) continue
                if (Regex("zip|complete|pack", RegexOption.IGNORE_CASE).containsMatchIn(label) &&
                    !Regex("single", RegexOption.IGNORE_CASE).containsMatchIn(label)) continue
            }
            out += Rel(label, m.groupValues[2].htmlUnescape())
        }
        return out.distinctBy { it.href }
    }

    private suspend fun nexdrive(rel: Rel, req: CinemaScrapeRequest, headers: Map<String, String>): List<HubLink> {
        val html = http.fetch(rel.href, headers, timeoutMs = 7_000, maxBytes = 1024 * 1024)?.takeIf { it.ok }?.text ?: return emptyList()
        if (req.isMovie) {
            val vc = Regex("""href=["'](https?://[^"']*vcloud[^"']*)["']""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
            if (vc != null) return resolver.resolve(vc.htmlUnescape())
            val hc = Regex("""href=["'](https?://[^"']*hubcloud[^"']*)["']""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
            return if (hc != null) resolver.resolve(hc.htmlUnescape()) else emptyList()
        }
        // series: "Episodes: N" caption followed by the G-Direct (fastdl) anchor
        val rx = Regex("""Episodes?:?\s*0*(\d+)[^<]*</[^>]+>.{0,400}?href=["']([^"']*fastdl[^"']*)["']""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val href = rx.findAll(html).firstOrNull { it.groupValues[1].toIntOrNull() == req.episode }?.groupValues?.get(2)
            ?: return emptyList()
        return listOfNotNull(resolver.fastdl(href.htmlUnescape()))
    }

    private companion object {
        const val MAX_RELEASES = 4
    }
}
