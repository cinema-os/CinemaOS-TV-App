package com.nuvio.tv.core.scrapers

/** Result of probing an HLS link: [maxHeight] from the master's RESOLUTION= (0 unknown), [playable] = first segment answered. */
class HlsInfo(val maxHeight: Int, val playable: Boolean)

private val RES_RX = Regex("RESOLUTION=(\\d+)x(\\d+)", RegexOption.IGNORE_CASE)

internal fun resolveHlsUrl(base: String, ref: String): String {
    val r = ref.trim()
    if (Regex("^https?:", RegexOption.IGNORE_CASE).containsMatchIn(r)) return r
    if (r.startsWith("/")) return (Regex("^https?://[^/]+", RegexOption.IGNORE_CASE).find(base)?.value ?: "") + r
    return base.replace(Regex("[?#].*$"), "").replace(Regex("[^/]*$"), "") + r
}

/**
 * Reads the master playlist (real RESOLUTION= of every variant, not the provider's label), then follows the best
 * variant to its first segment. Returns null when the master itself could not be reached in time (unknown: callers
 * keep the link), HlsInfo(playable=false) when the playlist chain answers with an error (expired / bad token).
 */
suspend fun CinemaHttp.probeHls(url: String, headers: Map<String, String>, timeoutMs: Long = 5_000): HlsInfo? {
    val master = fetch(url, headers, timeoutMs = timeoutMs, maxBytes = 512 * 1024) ?: return null
    if (!master.ok || !master.text.contains("#EXTM3U")) return HlsInfo(0, false)
    var maxH = 0
    var cur = url
    var text = master.text
    if (text.contains("#EXT-X-STREAM-INF")) {
        val lines = text.split('\n').map { it.trim() }
        var bestH = -1
        var bestUri: String? = null
        for (i in lines.indices) {
            if (!lines[i].startsWith("#EXT-X-STREAM-INF")) continue
            val h = RES_RX.find(lines[i])?.groupValues?.get(2)?.toIntOrNull() ?: 0
            val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: continue
            if (h > maxH) maxH = h
            if (h > bestH) { bestH = h; bestUri = uri }
        }
        val uri = bestUri ?: return HlsInfo(maxH, false)
        cur = resolveHlsUrl(url, uri)
        val variant = fetch(cur, headers, timeoutMs = timeoutMs, maxBytes = 512 * 1024) ?: return null
        if (!variant.ok || !variant.text.contains("#EXTM3U")) return HlsInfo(maxH, false)
        text = variant.text
    }
    val seg = text.split('\n').map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
        ?: return HlsInfo(maxH, false)
    val r = fetch(resolveHlsUrl(cur, seg), headers + ("Range" to "bytes=0-1023"), timeoutMs = timeoutMs, maxBytes = 1)
        ?: return null
    return HlsInfo(maxH, r.ok)
}
