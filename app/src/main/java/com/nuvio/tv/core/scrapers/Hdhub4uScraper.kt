package com.nuvio.tv.core.scrapers

import com.nuvio.tv.domain.model.LocalScraperResult
import java.net.URLEncoder

/**
 * hdhub4u: the site's search is client-side typesense on search.pingora.fyi. Post -> quality links (greenmotors
 * redirector / hubdrive / hubcdn) -> HubCloud servers. Distinct from [HdHubScraper] (the HDHub Stremio addon).
 */
class Hdhub4uScraper(private val http: CinemaHttp) : CinemaScraper {
    override val id = "cinemaos-hdhub4u"
    override val name = "CinemaOS · HDhub4u"
    override val description = "HDhub4u HubCloud MKV links (Hindi + English)"
    override val budgetMs = 28_000L

    private val resolver = HubResolver(http)
    private val domains = SiteDomains(http)

    private class Rel(val label: String, val href: String) {
        val quality = qualityNumber(label)
    }

    override suspend fun getStreams(req: CinemaScrapeRequest): List<LocalScraperResult> {
        if (!Regex("^\\d+$").matches(req.tmdbId)) return emptyList()
        if (System.currentTimeMillis() < skipUntil) return emptyList()
        val brief = http.tmdbBrief(if (req.isMovie) "movie" else "tv", req.tmdbId)
        if (brief.title.isBlank()) return emptyList()
        val bases = domains.candidates("hdhub4u", "https://new17.hdhub4u.cl")
        val base = bases.firstOrNull() ?: return emptyList()

        val permalink = search(brief, req, base) ?: return emptyList()
        val postUrl = if (permalink.startsWith("/")) base + permalink else base + "/" + permalink.substringAfter("://").substringAfter('/')
        val html = http.fetch(postUrl, mapOf("User-Agent" to CinemaHttp.SAFARI_UA), timeoutMs = 8_000, maxBytes = 3 * 1024 * 1024)
            ?.takeIf { it.ok }?.text ?: run { domains.markDead(base); return emptyList() }

        val rels = parseReleases(html, req)
        val chosen = rels.sortedBy { qualityRank(it.quality) }.take(MAX_RELEASES)
        val cands = ArrayList<HubCand>()
        parallelUntil<Rel, List<HubCand>>(chosen, timeoutMs = 16_000) { rel ->
            val links = resolver.resolve(rel.href)
            links.map { HubCand(it, it.file ?: rel.label, rel.quality.takeIf { q -> q > 0 } ?: qualityNumber(it.file.orEmpty()), it.size ?: sizeOf(rel.label), rel.label) }
                .takeIf { it.isNotEmpty() }
        }.forEach { cands += it }
        return http.verifyAndMap(cands, "HDhub4u")
    }

    private suspend fun search(brief: TmdbBrief, req: CinemaScrapeRequest, base: String): String? {
        val headers = mapOf(
            "User-Agent" to CinemaHttp.SAFARI_UA,
            "Cookie" to "xla=s4t",
            "Referer" to "$base/",
            "Origin" to base,
            "Accept" to "application/json"
        )
        suspend fun q(term: String): List<org.json.JSONObject> {
            val url = "$SEARCH?q=${URLEncoder.encode(term, "UTF-8")}&query_by=post_title,category,stars,director,imdb_id" +
                "&query_by_weights=4,2,2,2,4&sort_by=sort_by_date:desc&limit=10&highlight_fields=none&use_cache=true&page=1"
            val r = http.fetch(url, headers, timeoutMs = 6_000, maxBytes = 1024 * 1024)
            if (r == null) {
                if (consecutiveFailures.incrementAndGet() >= 2) { skipUntil = System.currentTimeMillis() + 5 * 60_000L; consecutiveFailures.set(0) }
                return emptyList()
            }
            consecutiveFailures.set(0)
            if (!r.ok) return emptyList()
            val hits = runCatching { org.json.JSONObject(r.text).optJSONArray("hits") }.getOrNull() ?: return emptyList()
            return (0 until hits.length()).mapNotNull { hits.optJSONObject(it)?.optJSONObject("document") }
        }

        val tried = LinkedHashSet<String>()
        val terms = listOfNotNull(brief.imdbId.takeIf { it.isNotBlank() }, brief.title)
        for (term in terms) {
            if (!tried.add(term)) continue
            for (d in q(term)) {
                val title = d.str("post_title") ?: continue
                val link = d.str("permalink") ?: continue
                val imdbOk = brief.imdbId.isNotBlank() && d.str("imdb_id") == brief.imdbId
                val isSeries = Regex("season|\\bs\\d{1,2}\\b|web series|episode", RegexOption.IGNORE_CASE).containsMatchIn(title)
                if (req.isMovie) {
                    if (isSeries && !imdbOk) continue
                    if (imdbOk || titleMatches(title, brief.title, brief.year, true)) return link
                } else {
                    if (!isSeries) continue
                    if (!titleMatches(title, brief.title, "", false)) continue
                    if (seasonCovered(title, req.season)) return link
                }
            }
        }
        return null
    }

    private fun seasonCovered(title: String, season: Int): Boolean {
        val range = Regex("season\\s*(\\d+)\\s*[-–to ]+\\s*(\\d+)", RegexOption.IGNORE_CASE).find(title)
        if (range != null) return season in range.groupValues[1].toInt()..range.groupValues[2].toInt()
        val single = Regex("season\\s*(\\d+)", RegexOption.IGNORE_CASE).find(title)?.groupValues?.get(1)?.toIntOrNull()
        return single == null || single == season
    }

    /** Anchors inside h3/h4 headings (480p / 720p / 1080p / 2160p ... labels); for series only the episode's link. */
    private fun parseReleases(html: String, req: CinemaScrapeRequest): List<Rel> {
        val out = ArrayList<Rel>()
        val rx = Regex("<h[2-5][^>]*>(.*?)</h[2-5]>", RegexOption.DOT_MATCHES_ALL)
        val a = Regex("""<a\s[^>]*href=["']([^"']+)["'][^>]*>(.*?)</a>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val epRx = Regex("(?:^|\\W)ep(?:isode)?\\.?\\s*0*${req.episode}(?:\\D|$)", RegexOption.IGNORE_CASE)
        var seasonOk = true
        for (m in rx.findAll(html)) {
            val inner = m.groupValues[1]
            val heading = inner.stripTags()
            if (!req.isMovie) {
                Regex("season\\s*0*(\\d+)", RegexOption.IGNORE_CASE).find(heading)?.let { seasonOk = it.groupValues[1].toInt() == req.season }
            }
            for (am in a.findAll(inner)) {
                val href = am.groupValues[1].htmlUnescape()
                val label = am.groupValues[2].stripTags()
                if (!Regex("greenmotors|hubdrive|hubcloud|hblinks", RegexOption.IGNORE_CASE).containsMatchIn(href)) continue
                if (Regex("zip|pack|complete", RegexOption.IGNORE_CASE).containsMatchIn(label)) continue
                if (req.isMovie) {
                    if (qualityNumber(label) == 0 && qualityNumber(heading) == 0) continue
                    out += Rel(heading.ifBlank { label }, href)
                } else if (seasonOk && epRx.containsMatchIn(label)) {
                    out += Rel(heading + " " + label, href)
                }
            }
        }
        return out.distinctBy { it.href }
    }

    private companion object {
        const val SEARCH = "https://search.pingora.fyi/collections/post/documents/search"
        const val MAX_RELEASES = 4
        @Volatile var skipUntil = 0L
        val consecutiveFailures = java.util.concurrent.atomic.AtomicInteger(0)
    }
}
