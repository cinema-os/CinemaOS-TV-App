package com.nuvio.tv.core.scrapers

import android.util.Base64
import org.json.JSONObject
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap

/** One playable candidate produced by the HubCloud-family resolvers. */
class HubLink(
    val url: String,
    val server: String,
    val size: String? = null,
    val file: String? = null,
    val headers: Map<String, String> = emptyMap()
)

internal fun String.stripTags(): String =
    replace(Regex("<[^>]+>"), " ").replace("&nbsp;", " ").replace("&amp;", "&").replace(Regex("\\s+"), " ").trim()

internal fun String.htmlUnescape(): String =
    replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value }
        .replace("&amp;", "&").replace("&#x26;", "&").replace("&quot;", "\"")

internal fun normTitle(s: String): String =
    s.lowercase().replace("&", " and ").replace(Regex("[^a-z0-9]+"), " ").trim()

internal fun qualityNumber(text: String): Int {
    if (Regex("2160p|\\b4k\\b|\\buhd\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) return 2160
    return Regex("(\\d{3,4})p", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
}

internal fun qualityLabel(n: Int): String = when {
    n >= 2160 -> "2160p"
    n > 0 -> "${n}p"
    else -> "HD"
}

/** 1080p first, then 720p, then 2160p, then the rest (480p last): same preference as HdHub. */
internal fun qualityRank(n: Int) = when {
    n == 1080 -> 0
    n == 720 -> 1
    n >= 2160 -> 2
    n == 480 -> 4
    else -> 3
}

internal fun sizeOf(text: String): String? =
    Regex("(\\d+(?:\\.\\d+)?)\\s*(GB|MB)", RegexOption.IGNORE_CASE).find(text)?.let { "${it.groupValues[1]} ${it.groupValues[2].uppercase()}" }

internal fun containerType(url: String): String =
    if (Regex("\\.mp4(\\?|&|$)", RegexOption.IGNORE_CASE).containsMatchIn(url)) "mp4" else "mkv"

/** Site domains: read from the community urls.json (domains rotate), with built-in fallbacks. Cached 6 h. */
internal class SiteDomains(private val http: CinemaHttp) {
    private suspend fun load() {
        if (System.currentTimeMillis() - at < 6 * 3_600_000L && map.isNotEmpty()) return
        if (System.currentTimeMillis() - at < 60_000L) return // a failed load is retried at most once a minute
        at = System.currentTimeMillis()
        val r = http.fetch(URLS_JSON, timeoutMs = 4_000, maxBytes = 64 * 1024, countResponse = false)
        if (r != null && r.ok) {
            val o = runCatching { JSONObject(r.text) }.getOrNull() ?: return
            val m = HashMap<String, String>()
            o.keys().forEach { k -> o.optString(k).takeIf { it.startsWith("http") }?.let { m[k] = it.trimEnd('/') } }
            map = m
        }
    }

    /** Candidate base URLs for [key]: the published one first, then the fallbacks. */
    suspend fun candidates(key: String, vararg fallbacks: String): List<String> {
        load()
        return (listOfNotNull(map[key]) + fallbacks.toList()).map { it.trimEnd('/') }.distinct()
            .filter { (CinemaHttp.deadHosts[CinemaHttp.hostOf(it)] ?: 0L) < System.currentTimeMillis() }
    }

    fun markDead(base: String) {
        CinemaHttp.deadHosts[CinemaHttp.hostOf(base)] = System.currentTimeMillis() + 5 * 60_000L
    }

    private companion object {
        // shared by every scraper: one urls.json read per process
        @Volatile var at = 0L
        @Volatile var map: Map<String, String> = emptyMap()
        const val URLS_JSON = "https://raw.githubusercontent.com/SaurabhKaperwan/Utils/refs/heads/main/urls.json"
    }
}

/**
 * Shared link-chain resolvers for the HubCloud family (HubCloud / HubDrive / VCloud / FastDL / GDirect) plus the
 * "greenmotors" redirector. Plain HTTP + regex only (no JS is executed, no gate / captcha is bypassed): when a
 * page does not hand out a link in its HTML, the chain returns nothing.
 */
internal class HubResolver(private val http: CinemaHttp) {
    private val cache = ConcurrentHashMap<String, Pair<Long, List<HubLink>>>()

    private suspend fun page(url: String, referer: String? = null, timeoutMs: Long = 8_000): String? {
        val h = HashMap<String, String>()
        h["User-Agent"] = CinemaHttp.SAFARI_UA
        h["Accept"] = "text/html,application/xhtml+xml,*/*;q=0.8"
        if (referer != null) h["Referer"] = referer
        val r = http.fetch(url, h, timeoutMs = timeoutMs, maxBytes = 2 * 1024 * 1024) ?: return null
        return if (r.ok) r.text else null
    }

    private fun rot13(s: String) = s.map {
        when (it) {
            in 'a'..'z' -> 'a' + (it - 'a' + 13) % 26
            in 'A'..'Z' -> 'A' + (it - 'A' + 13) % 26
            else -> it
        }
    }.joinToString("")

    private fun b64(s: String): String? = runCatching {
        String(Base64.decode(s.trim(), Base64.DEFAULT), Charsets.UTF_8)
    }.getOrNull()

    /**
     * greenmotors.* redirector: the page stores `s('o','<payload>')`. payload = base64(base64(rot13(base64(json))))
     * and json.o = base64(target URL). Other hosts pass through untouched.
     */
    suspend fun unwrap(url: String): String? {
        if (!Regex("greenmotors\\.", RegexOption.IGNORE_CASE).containsMatchIn(url)) return url
        val html = page(url, timeoutMs = 7_000) ?: return null
        val payload = Regex("""s\(\s*['"]o['"]\s*,\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1) ?: return null
        val step1 = b64(payload) ?: return null
        val step2 = b64(step1) ?: return null
        val json = b64(rot13(step2)) ?: return null
        val o = runCatching { JSONObject(json).optString("o") }.getOrNull().orEmpty()
        val target = b64(o)?.trim().orEmpty()
        return target.takeIf { it.startsWith("http") }
    }

    /** Resolves any supported release link (greenmotors / hubdrive / hubcloud / hblinks / vcloud / fastdl) to links. */
    suspend fun resolve(rawUrl: String, depth: Int = 0): List<HubLink> {
        if (depth > 3) return emptyList()
        val url = unwrap(rawUrl) ?: return emptyList()
        cache[url]?.let { if (System.currentTimeMillis() - it.first < 300_000) return it.second }
        val host = CinemaHttp.hostOf(url).lowercase()
        val out: List<HubLink> = when {
            host.contains("hubcloud") -> hubcloud(url)
            host.contains("vcloud") -> vcloud(url)
            host.contains("fastdl") -> listOfNotNull(fastdl(url))
            host.contains("hubdrive") || host.contains("hblinks") -> {
                val html = page(url) ?: return emptyList()
                val next = Regex("""href=["'](https?://[^"']*(?:hubcloud|vcloud)[^"']*)["']""", RegexOption.IGNORE_CASE)
                    .find(html)?.groupValues?.get(1)?.htmlUnescape()
                if (next != null) resolve(next, depth + 1) else emptyList()
            }
            else -> emptyList()
        }
        if (out.isNotEmpty()) cache[url] = System.currentTimeMillis() to out
        return out
    }

    /** hubcloud.*\/drive|video/<id> -> gamerxyt hubcloud.php page -> server buttons. */
    suspend fun hubcloud(url: String): List<HubLink> {
        val html = page(url) ?: return emptyList()
        val gate = Regex("""var\s+url\s*=\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)
            ?: Regex("""href=["'](https?://[^"']*hubcloud\.php[^"']*)["']""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
            ?: return emptyList()
        return serverPage(gate.htmlUnescape(), CinemaHttp.hostOf(url).let { "https://$it/" })
    }

    /** vcloud.* page: `var url = atob(atob('..'))` -> same server list as HubCloud. */
    suspend fun vcloud(url: String): List<HubLink> {
        val html = page(url) ?: return emptyList()
        val enc = Regex("""atob\(\s*atob\(\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)
        val next = enc?.let { b64(it)?.let { s -> b64(s) } }?.takeIf { it.startsWith("http") }
        return if (next != null) serverPage(next, url) else parseServers(html)
    }

    private suspend fun serverPage(url: String, referer: String): List<HubLink> {
        val html = page(url, referer) ?: return emptyList()
        return parseServers(html)
    }

    private suspend fun parseServers(html: String): List<HubLink> {
        val size = Regex("""id=["']size["'][^>]*>([^<]+)""").find(html)?.groupValues?.get(1)?.trim()
            ?: sizeOf(Regex("""File Size[^<]*<[^>]*>([^<]+)""").find(html)?.groupValues?.get(1).orEmpty())
        val file = Regex("<title>([^<]+)</title>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.trim()
        val anchors = Regex("""<a\s[^>]*href=["']([^"']+)["'][^>]*class=["'][^"']*btn[^"']*["'][^>]*>(.*?)</a>|<a\s[^>]*class=["'][^"']*btn[^"']*["'][^>]*href=["']([^"']+)["'][^>]*>(.*?)</a>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).findAll(html).toList()
        val direct = ArrayList<HubLink>()
        val redirects = ArrayList<Pair<String, String>>()
        for (m in anchors) {
            val href = (m.groupValues[1].ifEmpty { m.groupValues[3] }).htmlUnescape()
            val label = (m.groupValues[2].ifEmpty { m.groupValues[4] }).stripTags()
            if (!href.startsWith("http")) continue
            val h = href.lowercase()
            when {
                h.contains("/tg/go") || h.contains("t.me/") || h.contains("/admin") || h.contains("google.com/search") -> {}
                h.contains("r2.cloudflarestorage.com") -> direct += HubLink(href, "FSLv2", size, file)
                h.contains("pixeldrain") -> {
                    val id = Regex("/(?:u|api/file)/([A-Za-z0-9]+)").find(href)?.groupValues?.get(1)
                    if (id != null) direct += HubLink("${Regex("^https?://[^/]+").find(href)!!.value}/api/file/$id?download", "PixelDrain", size, file)
                }
                h.contains("workers.dev") && Regex("\\.(mkv|mp4|m4v|webm)(\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(href) ->
                    direct += HubLink(href, "Download File", size, file)
                label.contains("FSL", true) && Regex("\\.(mkv|mp4)(\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(href) ->
                    direct += HubLink(href, "FSL", size, file)
                label.contains("10Gbps", true) || label.contains("Pixel", true) || h.contains("pixel.hubcloud") || Regex("//gpdl\\d*\\.").containsMatchIn(h) ->
                    redirects += href to "10Gbps"
            }
        }
        // 10Gbps / PixelServer hops 302 through workers + cryptonewz to a Google video URL: follow, never execute anything.
        val resolved = redirects.take(2).mapNotNull { (href, label) -> followRedirect(href)?.let { HubLink(it, label, size, file) } }
        return (direct + resolved).distinctBy { it.url }
    }

    private suspend fun followRedirect(href: String): String? {
        val r = http.fetch(
            href,
            mapOf("User-Agent" to CinemaHttp.SAFARI_UA, "Range" to "bytes=0-0"),
            timeoutMs = 8_000, maxBytes = 1
        ) ?: return null
        val final = r.finalUrl
        val link = Regex("[?&]link=(https?[^&]+)").find(final)?.groupValues?.get(1)
        val picked = link?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) } ?: final
        return picked.takeIf { r.ok && Regex("googleusercontent|\\.mkv|\\.mp4|workers\\.dev", RegexOption.IGNORE_CASE).containsMatchIn(it) }
    }

    /** fastdl.beer/embed?download=ID -> `var reurl = ".../dl.php?link=<google url>"`. */
    suspend fun fastdl(url: String): HubLink? {
        val html = page(url) ?: return null
        val re = Regex("""reurl\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1) ?: return null
        val link = Regex("[?&]link=(https?.+)$").find(re)?.groupValues?.get(1)?.let {
            runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it)
        } ?: re
        return HubLink(link, "G-Direct")
    }

    /** hshare.lol/?id=<file> -> HindShare page -> gdirect.online redirect page -> Google video URL. */
    suspend fun hshare(url: String): HubLink? {
        val r = http.fetch(url, mapOf("User-Agent" to CinemaHttp.SAFARI_UA), timeoutMs = 7_000, maxBytes = 512 * 1024) ?: return null
        if (!r.ok) return null
        val html = r.text
        val size = Regex("Size:\\s*(?:</strong>)?\\s*([\\d.]+\\s*[GM]B)", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
        val gd = Regex("""href=["'](https?://gdirect\.[^"']+)["']""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.htmlUnescape()
            ?: return null
        val g = page(gd, referer = r.finalUrl.ifEmpty { "https://hshare.lol/" }, timeoutMs = 7_000) ?: return null
        val video = Regex("""https://video-downloads\.googleusercontent\.com/[^"'\s<>]+""").find(g)?.value?.htmlUnescape() ?: return null
        return HubLink(video, "GDirect", size)
    }
}

/** A release link plus the page context it was found under. */
internal class HubCand(
    val link: HubLink,
    val title: String,
    val quality: Int,
    val size: String?,
    val langText: String
)

/**
 * Range-probes every candidate (head + tail checks in [CinemaHttp.isVideo]) concurrently and keeps only the
 * playable ones, best quality first. Dead / HTML / Cloudflare-challenge answers never reach the list.
 */
internal suspend fun CinemaHttp.verifyAndMap(
    cands: List<HubCand>,
    provider: String,
    timeoutMs: Long = 9_000
): List<com.nuvio.tv.domain.model.LocalScraperResult> {
    val ok = parallelUntil<HubCand, HubCand>(items = cands.distinctBy { it.link.url }.take(24), timeoutMs = timeoutMs) { c ->
        if (isVideo(c.link.url, c.link.headers, timeoutMs = timeoutMs - 1_500)) c else null
    }
    return ok.sortedWith(compareBy<HubCand>({ qualityRank(it.quality) }, { it.link.server })).map { c ->
        val clean = c.title.replace(Regex("\\.(mkv|mp4|avi|webm|m4v)$", RegexOption.IGNORE_CASE), "")
            .replace('.', ' ').replace('_', ' ').trim()
        com.nuvio.tv.domain.model.LocalScraperResult(
            name = "$provider · ${c.link.server} · ${qualityLabel(c.quality)}",
            title = clean.ifBlank { provider },
            language = com.nuvio.tv.core.player.StreamLanguage.normalisedLabel("${c.langText} ${c.title}"),
            url = c.link.url,
            quality = qualityLabel(c.quality),
            size = (c.link.size ?: c.size)?.takeIf { it.isNotBlank() },
            provider = provider,
            type = containerType(c.link.url),
            headers = c.link.headers
        )
    }
}

/** True when the release title is for the requested title (+ year for movies, within one year). */
internal fun titleMatches(candidate: String, title: String, year: String, isMovie: Boolean): Boolean {
    if (title.isBlank()) return false
    val c = " " + normTitle(candidate) + " "
    val t = normTitle(title)
    val variants = linkedSetOf(t, t.removePrefix("the "))
    if (!c.contains(" ${variants.first()} ") && variants.none { c.contains(" $it ") }) return false
    if (isMovie && year.length == 4) {
        val y = Regex("\\b(19|20)\\d\\d\\b").find(candidate)?.value?.toIntOrNull() ?: return true
        if (Math.abs(y - year.toInt()) > 1) return false
    }
    return true
}
