package com.nuvio.tv.core.scrapers

import com.nuvio.tv.domain.model.LocalScraperResult
import java.net.URLEncoder

/**
 * Hindmoviez (hindmovie.im): `/?s=` search -> post -> mvlink.blog page -> hshare.lol/?id=<file> -> HindShare page
 * -> gdirect.online -> Google video URL (Hindi + English MKV). The plain (unsigned) hshare link is used, no secret.
 * The Google URLs are short lived, so they are resolved at scrape time (the stream model has no lazy resolver).
 */
class HindmoviezScraper(private val http: CinemaHttp) : CinemaScraper {
    override val id = "cinemaos-hindmoviez"
    override val name = "CinemaOS · Hindmoviez"
    override val description = "Hindmoviez Hindi + English dual audio MKV (Google direct)"
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
        val base = domains.candidates("hindmoviez_site", "https://v5.hindmovie.im").firstOrNull() ?: return emptyList()
        val headers = mapOf("User-Agent" to CinemaHttp.SAFARI_UA, "Accept" to "text/html,*/*;q=0.8")

        val searchHtml = http.fetch("$base/?s=${URLEncoder.encode(brief.title, "UTF-8")}", headers, timeoutMs = 7_000, maxBytes = 2 * 1024 * 1024)
            ?.takeIf { it.ok }?.text ?: run { domains.markDead(base); return emptyList() }
        val post = pickPost(searchHtml, brief, req) ?: return emptyList()
        val html = http.fetch(post, headers, timeoutMs = 8_000, maxBytes = 3 * 1024 * 1024)?.takeIf { it.ok }?.text ?: return emptyList()

        val rels = parseReleases(html, req)
        val chosen = rels.sortedBy { qualityRank(it.quality) }.take(MAX_RELEASES)
        val cands = ArrayList<HubCand>()
        // Keep this chain short: stale mvlink ids 404, and Google links are probed right after they are resolved.
        parallelUntil<Rel, HubCand>(chosen, timeoutMs = 18_000) { rel ->
            val hs = hshareLink(rel, req, headers) ?: return@parallelUntil null
            val file = Regex("[?&]id=([^&]+)").find(hs)?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: rel.label
            val link = resolver.hshare(hs) ?: return@parallelUntil null
            HubCand(link, file, rel.quality.takeIf { it > 0 } ?: qualityNumber(file), link.size ?: sizeOf(rel.label), "${rel.label} $file")
        }.forEach { cands += it }
        return http.verifyAndMap(cands, "Hindmoviez")
    }

    private fun pickPost(html: String, brief: TmdbBrief, req: CinemaScrapeRequest): String? {
        val rx = Regex("""entry-title-link"[^>]*href="([^"]+)"[^>]*>([^<]+)""")
        for (m in rx.findAll(html)) {
            val title = m.groupValues[2].htmlUnescape()
            val isSeries = Regex("season", RegexOption.IGNORE_CASE).containsMatchIn(title)
            if (req.isMovie) {
                if (!isSeries && titleMatches(title, brief.title, brief.year, true)) return m.groupValues[1]
            } else if (isSeries && titleMatches(title, brief.title, "", false)) {
                val range = Regex("season\\s*(\\d+)\\s*[-–]\\s*(\\d+)", RegexOption.IGNORE_CASE).find(title)
                val single = Regex("season\\s*(\\d+)", RegexOption.IGNORE_CASE).find(title)?.groupValues?.get(1)?.toIntOrNull()
                val ok = if (range != null) req.season in range.groupValues[1].toInt()..range.groupValues[2].toInt() else single == null || single == req.season
                if (ok) return m.groupValues[1]
            }
        }
        return null
    }

    /** Heading + following maxbutton anchor (mvlink.blog/<id> for movies, mvlink.blog/web/<id> "Episode List"). */
    private fun parseReleases(html: String, req: CinemaScrapeRequest): List<Rel> {
        val out = ArrayList<Rel>()
        val body = html.substringAfter("entry-content", html)
        val rx = Regex("""<h(\d)[^>]*>(.*?)</h\1>|<a\s[^>]*maxbutton[^>]*href=["']([^"']+)["']""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        var heading = ""
        for (m in rx.findAll(body)) {
            if (m.groupValues[2].isNotEmpty() && m.groupValues[3].isEmpty()) { heading = m.groupValues[2].stripTags(); continue }
            val href = m.groupValues[3]
            if (!href.contains("mvlink")) continue
            if (!req.isMovie) {
                val s = Regex("season\\s*0*(\\d+)", RegexOption.IGNORE_CASE).find(heading)?.groupValues?.get(1)?.toIntOrNull()
                if (s != req.season) continue
            }
            out += Rel(heading, href)
        }
        return out.distinctBy { it.href }
    }

    /** mvlink page -> the hshare.lol link (movie: "Get Links"; series: the Episode NN anchor). */
    private suspend fun hshareLink(rel: Rel, req: CinemaScrapeRequest, headers: Map<String, String>): String? {
        val html = http.fetch(rel.href, headers, timeoutMs = 7_000, maxBytes = 1024 * 1024)?.takeIf { it.ok }?.text ?: return null
        val rx = Regex("""<a\s[^>]*href=["'](https?://hshare\.[^"']+)["'][^>]*>(.*?)</a>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val all = rx.findAll(html).map { it.groupValues[1].htmlUnescape() to it.groupValues[2].stripTags() }.toList()
        if (req.isMovie) return all.firstOrNull()?.first?.let { encodeSpaces(it) }
        val ep = Regex("episode\\s*0*${req.episode}(?!\\d)", RegexOption.IGNORE_CASE)
        return all.firstOrNull { ep.containsMatchIn(it.second) }?.first?.let { encodeSpaces(it) }
    }

    private fun encodeSpaces(u: String) = u.replace(" ", "%20")

    private companion object {
        const val MAX_RELEASES = 3
    }
}
