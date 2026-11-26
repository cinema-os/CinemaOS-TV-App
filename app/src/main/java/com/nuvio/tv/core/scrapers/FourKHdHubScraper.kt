package com.nuvio.tv.core.scrapers

import com.nuvio.tv.domain.model.LocalScraperResult
import java.net.URLEncoder

/**
 * 4KHDHub (4khdhub.one): HTML search -> movie / series page -> release blocks with "Download HubCloud / HubDrive"
 * buttons (greenmotors redirector) -> HubCloud servers (FSL, 10Gbps, Download File, PixelDrain).
 */
class FourKHdHubScraper(private val http: CinemaHttp) : CinemaScraper {
    override val id = "cinemaos-4khdhub"
    override val name = "CinemaOS · 4KHDHub"
    override val description = "4KHDHub HubCloud MKV links (1080p to 4K REMUX)"
    override val budgetMs = 28_000L

    private val resolver = HubResolver(http)
    private val domains = SiteDomains(http)

    private class Rel(val title: String, val quality: Int, val size: String?, val langText: String, val hrefs: List<String>)

    override suspend fun getStreams(req: CinemaScrapeRequest): List<LocalScraperResult> {
        if (!Regex("^\\d+$").matches(req.tmdbId)) return emptyList()
        val brief = http.tmdbBrief(if (req.isMovie) "movie" else "tv", req.tmdbId)
        if (brief.title.isBlank()) return emptyList()
        val base = domains.candidates("4khdhub", "https://4khdhub.one").firstOrNull() ?: return emptyList()
        val headers = mapOf("User-Agent" to CinemaHttp.SAFARI_UA, "Accept" to "text/html,*/*;q=0.8")

        val q = URLEncoder.encode((brief.title + " " + brief.year).trim(), "UTF-8")
        val searchHtml = http.fetch("$base/?s=$q", headers, timeoutMs = 7_000, maxBytes = 2 * 1024 * 1024)
            ?.takeIf { it.ok }?.text ?: run { domains.markDead(base); return emptyList() }
        val pageUrl = pickPost(searchHtml, brief, req, base) ?: return emptyList()
        val html = http.fetch(pageUrl, headers, timeoutMs = 8_000, maxBytes = 4 * 1024 * 1024)?.takeIf { it.ok }?.text ?: return emptyList()

        val rels = if (req.isMovie) parseMovie(html) else parseEpisodes(html, req)
        val chosen = rels.sortedBy { qualityRank(it.quality) }.take(MAX_RELEASES)
        val cands = ArrayList<HubCand>()
        parallelUntil<Rel, List<HubCand>>(chosen, timeoutMs = 16_000) { rel ->
            // HubCloud first (it carries FSL / 10Gbps); HubDrive is only the fallback
            val ordered = rel.hrefs.sortedBy { if (it.contains("hubcloud", true)) 0 else 1 }
            var links = emptyList<HubLink>()
            for (h in ordered) {
                links = resolver.resolve(h)
                if (links.isNotEmpty()) break
            }
            links.map { HubCand(it, it.file ?: rel.title, rel.quality.takeIf { q -> q > 0 } ?: qualityNumber(it.file.orEmpty()), it.size ?: rel.size, rel.langText) }
                .takeIf { it.isNotEmpty() }
        }.forEach { cands += it }
        return http.verifyAndMap(cands, "4KHDHub", timeoutMs = 10_000)
    }

    /** `.movie-card` entries: href + title + year; movies end in "-movie-N", series in "-series-N". */
    private fun pickPost(html: String, brief: TmdbBrief, req: CinemaScrapeRequest, base: String): String? {
        val card = Regex("""<a\s[^>]*href=["']([^"']+)["'][^>]*class=["'][^"']*movie-card[^"']*["'][^>]*>(.*?)</a>""",
            RegexOption.DOT_MATCHES_ALL)
        for (m in card.findAll(html)) {
            val href = m.groupValues[1]
            val body = m.groupValues[2]
            val isSeries = href.contains("-series-")
            if (isSeries == req.isMovie) continue
            val title = Regex("""movie-card-title[^>]*>([^<]+)""").find(body)?.groupValues?.get(1)?.htmlUnescape()?.trim().orEmpty()
            val year = Regex("""movie-card-meta[^>]*>\s*(\d{4})""").find(body)?.groupValues?.get(1)
            if (normTitle(title) != normTitle(brief.title) && !titleMatches(title, brief.title, "", false)) continue
            if (req.isMovie && year != null && brief.year.length == 4 && Math.abs(year.toInt() - brief.year.toInt()) > 1) continue
            return if (href.startsWith("http")) href else base + (if (href.startsWith("/")) href else "/$href")
        }
        return null
    }

    private fun hrefsIn(block: String): List<String> =
        Regex("""href=["']([^"']*(?:greenmotors|hubcloud|hubdrive)[^"']*)["']""", RegexOption.IGNORE_CASE)
            .findAll(block).map { it.groupValues[1].htmlUnescape() }.toList()

    private fun parseMovie(html: String): List<Rel> {
        val out = ArrayList<Rel>()
        val parts = html.split("class=\"download-item").drop(1)
        for (p in parts) {
            val head = p.substringBefore("content-file").stripTags()
            val fileTitle = Regex("""file-title">([^<]+)""").find(p)?.groupValues?.get(1)?.trim().orEmpty()
            val hrefs = hrefsIn(p.substringAfter("content-file", ""))
            if (hrefs.isEmpty()) continue
            val badges = Regex("""<span class="badge"[^>]*>([^<]+)</span>""").findAll(p.substringBefore("grid grid-cols")).map { it.groupValues[1] }.toList()
            out += Rel(
                title = fileTitle.ifBlank { head },
                quality = qualityNumber(fileTitle + " " + head),
                size = badges.firstOrNull { Regex("\\d\\s*(GB|MB)", RegexOption.IGNORE_CASE).containsMatchIn(it) }?.let { sizeOf(it) },
                langText = badges.joinToString(" "),
                hrefs = hrefs
            )
        }
        return out
    }

    private fun parseEpisodes(html: String, req: CinemaScrapeRequest): List<Rel> {
        val out = ArrayList<Rel>()
        val tag = Regex("S0*${req.season}E0*${req.episode}(?!\\d)", RegexOption.IGNORE_CASE)
        for (p in html.split("class=\"episode-download-item").drop(1)) {
            val fileTitle = Regex("""episode-file-title">\s*([^<]+)""").find(p)?.groupValues?.get(1)?.trim().orEmpty()
            if (!tag.containsMatchIn(fileTitle)) continue
            val hrefs = hrefsIn(p.substringBefore("episode-download-item"))
            if (hrefs.isEmpty()) continue
            out += Rel(fileTitle, qualityNumber(fileTitle), Regex("""badge-size">([^<]+)""").find(p)?.groupValues?.get(1)?.let { sizeOf(it) }, fileTitle, hrefs)
        }
        return out
    }

    private companion object {
        const val MAX_RELEASES = 4
    }
}
