package com.nuvio.tv.core.player

import com.nuvio.tv.domain.model.Stream
import java.util.concurrent.ConcurrentHashMap

/**
 * MovieBox returns the same title/quality once per audio language (plugin title
 * "MovieBox · 1080p · Hindi"). The sources list shows one entry per quality; the other
 * languages are registered here and offered in the player's audio panel.
 */
object MovieBoxLanguageVariants {
    data class Variant(val language: String, val stream: Stream)

    private const val SEP = " · "
    private const val MAX_ENTRIES = 4000
    private val byUrl = ConcurrentHashMap<String, List<Variant>>()

    private fun languageOf(stream: Stream): String? {
        if (!stream.addonName.contains("MovieBox", ignoreCase = true)) return null
        val parts = stream.title?.split(SEP) ?: return null
        if (parts.size < 3 || !parts[0].equals("MovieBox", ignoreCase = true)) return null
        return parts.drop(2).joinToString(SEP).trim().takeIf { it.isNotEmpty() }
    }

    private fun isOriginal(language: String): Boolean =
        language.equals("Original", true) || language.equals("English", true)

    /** Collapses language variants of the same quality into one stream and registers the group. */
    fun collapse(streams: List<Stream>): List<Stream> {
        registerAnimeAudioVariants(streams)
        if (streams.none { languageOf(it) != null }) return streams
        val groups = LinkedHashMap<String, MutableList<Stream>>()
        val result = ArrayList<Any>() // Stream or group key (String), preserving order
        streams.forEach { stream ->
            if (languageOf(stream) == null) {
                result += stream
            } else {
                val key = "${stream.addonName}|${stream.name}|${stream.title?.split(SEP)?.getOrNull(1)}"
                val list = groups.getOrPut(key) { result += key; mutableListOf() }
                list += stream
            }
        }
        if (byUrl.size > MAX_ENTRIES) byUrl.clear()
        return result.mapNotNull { item ->
            if (item is Stream) return@mapNotNull item
            val members = groups.getValue(item as String)
            val variants = members.mapNotNull { m ->
                val url = m.getStreamUrl() ?: return@mapNotNull null
                url to Variant(languageOf(m).orEmpty(), m)
            }.distinctBy { it.first }
            if (variants.isEmpty()) return@mapNotNull null
            val list = variants.map { it.second }
            variants.forEach { (url, _) -> byUrl[url] = list }
            val preferred = autoPlayPreferredAudioHint
            val rep = (list.firstOrNull { StreamLanguage.detect(it.language).matches(preferred) }
                ?: list.firstOrNull { isOriginal(it.language) } ?: list.first()).stream
            if (list.size > 1) {
                val quality = rep.title?.split(SEP)?.getOrNull(1)
                rep.copy(title = listOfNotNull("MovieBox", quality).joinToString(SEP))
            } else {
                rep
            }
        }
    }

    /**
     * Anime: the audio panel offers "Japanese (Sub)", "English (Dub)" and other languages. Streams of one anime
     * provider are grouped by audio label (best quality each); each of the provider's URLs maps to that list, so
     * picking an entry switches to the matching source at the current position (same path as MovieBox variants).
     */
    private fun registerAnimeAudioVariants(streams: List<Stream>) {
        val anime = streams.filter { animeProviderRank(it.addonName) > 0 && it.getStreamUrl() != null }
        if (anime.isEmpty()) return
        anime.groupBy { it.addonName }.values.forEach { group ->
            val best = LinkedHashMap<String, Stream>()
            group.forEach { st ->
                val info = StreamLanguage.of(st)
                val lang = info.primary ?: return@forEach
                val label = when (info.kind) {
                    AudioKind.DUB -> "$lang (Dub)"
                    AudioKind.SUB -> "$lang (Sub)"
                    else -> lang
                }
                val cur = best[label]
                if (cur == null || DeviceVideoCapabilities.declaredHeight(st) > DeviceVideoCapabilities.declaredHeight(cur)) best[label] = st
            }
            if (best.size < 2) return@forEach
            if (byUrl.size > MAX_ENTRIES) byUrl.clear()
            val list = best.entries
                .sortedBy { (label, _) -> if (label.endsWith("(Sub)")) 0 else if (label == "English (Dub)") 1 else 2 }
                .map { Variant(it.key, it.value) }
            group.forEach { st -> st.getStreamUrl()?.let { byUrl[it] = list } }
        }
    }

    /** Language variants of the MovieBox stream playing [url] (empty when it has none or is not MovieBox). */
    fun variantsFor(url: String?): List<Variant> {
        if (url.isNullOrBlank()) return emptyList()
        return byUrl[url]?.takeIf { it.size > 1 } ?: emptyList()
    }
}
