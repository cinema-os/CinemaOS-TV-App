package com.nuvio.tv.core.scrapers

import com.nuvio.tv.domain.model.LocalScraperResult
import java.net.URLEncoder

/**
 * Movies4u: the HTML `?s=` search needs a browser cookie, so posts are found through the WordPress REST search
 * (`/wp-json/wp/v2/search`). Post -> m4ulinks.xyz/number/<id> -> hubcloud.mov (drive|video) -> HubCloud servers.
 */
class Movies4uScraper(private val http: CinemaHttp) : CinemaScraper {
    override val id = "cinemaos-movies4u"
    override val name = "CinemaOS · Movies4u"
    override val description = "Movies4u HubCloud MKV links (Hindi + English, multi audio)"
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
        val base = domains.candidates("movies4u_site", "https://new9.movies4u.garden").firstOrNull() ?: return emptyList()
        val headers = mapOf("User-Agent" to CinemaHttp.SAFARI_UA, "Accept" to "text/html,application/json,*/*;q=0.8")

        val post = search(base, brief, req, headers) ?: return emptyList()
        val html = http.fetch(post, headers, timeoutMs = 8_000, maxBytes = 3 * 1024 * 1024)?.takeIf { it.ok }?.text
            ?: run { domains.markDead(base); return emptyList() }
        val rels = parseReleases(html, req)
        val chosen = rels.sortedBy { qualityRank(it.quality) }.take(MAX_RELEASES)
        val cands = ArrayList<HubCand>()
        parallelUntil<Rel, List<HubCand>>(chosen, timeoutMs = 16_000) { rel ->
            val links = linksFor(rel, req, headers)
            links.map { HubCand(it, it.file ?: rel.label, rel.quality.takeIf { q -> q > 0 } ?: qualityNumber(it.file.orEmpty()), it.size ?: sizeOf(rel.label), rel.label) }
                .takeIf { it.isNotEmpty() }
        }.forEach { cands += it }
        return http.verifyAndMap(cands, "Movies4u")
    }

    private suspend fun search(base: String, brief: TmdbBrief, req: CinemaScrapeRequest, headers: Map<String, String>): String? {
        val url = "$base/wp-json/wp/v2/search?search=${URLEncoder.encode(brief.title, "UTF-8")}&per_page=15&_fields=title,url"
        val r = http.fetch(url, headers, timeoutMs = 6_000, maxBytes = 1024 * 1024)
        if (r == null) { domains.markDead(base); return null }
        val arr = runCatching { org.json.JSONArray(r.text) }.getOrNull() ?: return null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.str("title")?.htmlUnescape() ?: continue
            val link = o.str("url") ?: continue
            val isSeries = Regex("season|web series|episode", RegexOption.IGNORE_CASE).containsMatchIn(title)
            if (req.isMovie) {
                if (!isSeries && titleMatches(title, brief.title, brief.year, true)) return link
            } else if (isSeries && titleMatches(title, brief.title, "", false)) {
                val s = Regex("season\\s*0*(\\d+)", RegexOption.IGNORE_CASE).find(title)?.groupValues?.get(1)?.toIntOrNull()
                if (s == null || s == req.season) return link
            }
        }
        return null
    }

    /** Each `<h4>` quality heading is followed by its m4ulinks "Download Links" button. */
    private fun parseReleases(html: String, req: CinemaScrapeRequest): List<Rel> {
        val out = ArrayList<Rel>()
        val rx = Regex("""<h4[^>]*>(.*?)</h4>\s*<div class="downloads-btns-div">\s*<a\s[^>]*href=["']([^"']*m4ulinks[^"']*)["'][^>]*>""",
            RegexOption.DOT_MATCHES_ALL)
        for (m in rx.findAll(html)) {
            val label = m.groupValues[1].stripTags()
            if (!req.isMovie) {
                val s = Regex("season\\s*0*(\\d+)", RegexOption.IGNORE_CASE).find(label)?.groupValues?.get(1)?.toIntOrNull()
                if (s != null && s != req.season) continue
            }
            out += Rel(label, m.groupValues[2].htmlUnescape())
        }
        return out.distinctBy { it.href }
    }

    private suspend fun linksFor(rel: Rel, req: CinemaScrapeRequest, headers: Map<String, String>): List<HubLink> {
        val html = http.fetch(rel.href, headers, timeoutMs = 7_000, maxBytes = 1024 * 1024)?.takeIf { it.ok }?.text ?: return emptyList()
        val hub = Regex("""href=["'](https?://[^"']*hubcloud[^"']*/(?:drive|video)/[^"']+)["']""", RegexOption.IGNORE_CASE)
        if (req.isMovie) {
            val u = hub.find(html)?.groupValues?.get(1) ?: return emptyList()
            return resolver.resolve(u.htmlUnescape())
        }
        // series: "Episodes: N" heading, then that episode's hubcloud button
        val rx = Regex("""Episodes?:?\s*0*(\d+)[^<]*</h5>(.{0,900}?)(?=Episodes?:?\s*0*\d+[^<]*</h5>|$)""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val block = rx.findAll(html).firstOrNull { it.groupValues[1].toIntOrNull() == req.episode }?.groupValues?.get(2) ?: return emptyList()
        val u = hub.find(block)?.groupValues?.get(1) ?: return emptyList()
        return resolver.resolve(u.htmlUnescape())
    }

    private companion object {
        const val MAX_RELEASES = 4
    }
}
