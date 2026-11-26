package com.nuvio.tv.core.player

import com.nuvio.tv.domain.model.Stream

/** Preferred audio language hint for auto-play ranking; kept in sync by PlayerSettingsDataStore. */
@Volatile var autoPlayPreferredAudioHint: String = "English"

enum class AudioKind { ORIGINAL, DUB, SUB, DUAL, MULTI, UNKNOWN }

/**
 * Normalised audio info for a stream. [languages] are canonical names ("English", "Hindi", "Portuguese (BR)",
 * "Spanish (LatAm)", "Japanese"...). "Original Audio" is reported as ORIGINAL with language "English" (the
 * title's own audio; for non-English titles the caller can still treat ORIGINAL as the original language).
 */
data class AudioInfo(val languages: List<String>, val kind: AudioKind) {
    val primary: String? get() = languages.firstOrNull()
    val hasLanguage: Boolean get() = languages.isNotEmpty()

    /** "English", "Portuguese (BR)" or "Hindi + English"; null when unknown. */
    fun label(): String? = languages.takeIf { it.isNotEmpty() }?.joinToString(" + ")

    fun matches(preferred: String): Boolean {
        if (preferred.equals("Original", true)) return kind == AudioKind.ORIGINAL || kind == AudioKind.SUB
        return languages.any { it.startsWith(preferred, ignoreCase = true) }
    }
}

object StreamLanguage {
    private class Lang(val name: String, val rx: Regex)

    private fun l(name: String, rx: String) = Lang(name, Regex(rx, RegexOption.IGNORE_CASE))

    // Order matters: the specific regional variants come before their generic language.
    private val LANGS = listOf(
        l("Portuguese (BR)", "\\bpt[-_ ]?br\\b|\\bptbr\\b|brazilian|\\bbrazil\\b|portugu[eê]s[-_ ]?br"),
        l("Portuguese", "\\bportuguese\\b|\\bpor\\b"),
        l("Spanish (LatAm)", "\\bes[-_ ]?la\\b|\\besla\\b|latino|latam|latin american|spanish[-_ ]?\\(?latin"),
        l("Spanish", "\\bspanish\\b|\\bespa[nñ]ol\\b|castellano|\\bspa\\b|\\besp\\b"),
        l("Hindi", "\\bhindi\\b|\\bhin\\b"),
        l("Tamil", "\\btamil\\b"),
        l("Telugu", "\\btelugu\\b"),
        l("Malayalam", "\\bmalayalam\\b"),
        l("Kannada", "\\bkannada\\b"),
        l("Bengali", "\\bbengali\\b"),
        l("Japanese", "\\bjapanese\\b|\\bjpn\\b|\\bjap\\b"),
        l("Korean", "\\bkorean\\b|\\bkor\\b"),
        l("French", "\\bfrench\\b|\\bfran[cç]ais\\b|\\bfre\\b|\\bfra\\b|\\bvf\\b|\\bvostfr\\b"),
        l("German", "\\bgerman\\b|\\bdeutsch\\b"),
        l("Italian", "\\bitalian\\b"),
        l("Russian", "\\brussian\\b"),
        l("Arabic", "\\barabic\\b"),
        l("Chinese", "\\bchinese\\b|\\bmandarin\\b|\\bcantonese\\b"),
        l("Turkish", "\\bturkish\\b"),
        l("English", "\\benglish\\b|\\beng\\b|\\ben\\b")
    )

    private val RX_ORIGINAL = Regex("\\boriginal(\\s+audio)?\\b", RegexOption.IGNORE_CASE)
    private val RX_DUB = Regex("\\bdub(bed|s)?\\b", RegexOption.IGNORE_CASE)
    private val RX_SUB = Regex("\\bsub(bed|s|titled)?\\b|softsub|hardsub|\\bvostfr\\b", RegexOption.IGNORE_CASE)
    private val RX_DUAL = Regex("\\bdual([-_ ]?audio)?\\b", RegexOption.IGNORE_CASE)
    private val RX_MULTI = Regex("\\bmulti([-_ ]?(audio|lang\\w*))?\\b", RegexOption.IGNORE_CASE)

    fun detect(text: String): AudioInfo {
        val t = text.replace('_', ' ').replace('.', ' ')
        val found = ArrayList<String>()
        var scan = t
        for (lang in LANGS) {
            if (lang.rx.containsMatchIn(scan)) {
                // "Spanish (LatAm)" already consumed "Spanish"; avoid reporting both a regional + generic variant
                val generic = lang.name.substringBefore(" (")
                if (lang.name.contains("(") || found.none { it.substringBefore(" (") == generic }) found += lang.name
            }
        }
        val languages = found.distinct()
        val isOriginal = RX_ORIGINAL.containsMatchIn(t)
        val kind = when {
            RX_MULTI.containsMatchIn(t) || languages.size >= 3 -> AudioKind.MULTI
            RX_DUAL.containsMatchIn(t) || languages.size == 2 -> AudioKind.DUAL
            RX_DUB.containsMatchIn(t) -> AudioKind.DUB
            RX_SUB.containsMatchIn(t) && !RX_ORIGINAL.containsMatchIn(t) -> AudioKind.SUB
            isOriginal -> AudioKind.ORIGINAL
            else -> AudioKind.UNKNOWN
        }
        var langs = languages
        if (langs.isEmpty()) {
            langs = when (kind) {
                AudioKind.ORIGINAL -> listOf("English")
                AudioKind.DUB -> listOf("English")
                AudioKind.SUB -> listOf("Japanese")
                else -> emptyList()
            }
        }
        // "ENG SUB" on a stream means English subtitles over the original (Japanese) audio
        if (kind == AudioKind.SUB && langs == listOf("English")) langs = listOf("Japanese")
        return AudioInfo(langs, kind)
    }

    /** Derives info from every text field of a stream (title carries MovieBox's raw lanName, description the language). */
    fun of(stream: Stream): AudioInfo {
        val urlName = stream.getStreamUrl()?.substringBefore('?')?.substringAfterLast('/').orEmpty()
        val text = listOfNotNull(
            stream.title, stream.description, stream.behaviorHints?.filename, stream.name, urlName
        ).joinToString(" | ")
        return detect(text)
    }

    /** Short badge text: "DUB", "SUB", "DUAL"... */
    fun kindBadge(kind: AudioKind): String? = when (kind) {
        AudioKind.DUB -> "DUB"
        AudioKind.SUB -> "SUB"
        AudioKind.DUAL -> "DUAL"
        AudioKind.MULTI -> "MULTI"
        AudioKind.ORIGINAL -> "ORIG"
        AudioKind.UNKNOWN -> null
    }

    fun flag(language: String): String = when (language.substringBefore(" (")) {
        "English" -> "🇬🇧"
        "Hindi", "Tamil", "Telugu", "Malayalam", "Kannada", "Bengali" -> "🇮🇳"
        "Japanese" -> "🇯🇵"
        "Korean" -> "🇰🇷"
        "Portuguese" -> if (language.contains("BR")) "🇧🇷" else "🇵🇹"
        "Spanish" -> if (language.contains("LatAm")) "🇲🇽" else "🇪🇸"
        "French" -> "🇫🇷"
        "German" -> "🇩🇪"
        "Italian" -> "🇮🇹"
        "Russian" -> "🇷🇺"
        "Arabic" -> "🇸🇦"
        "Chinese" -> "🇨🇳"
        "Turkish" -> "🇹🇷"
        else -> "🌐"
    }

    /** Normalised "language" string for scrapers' LocalScraperResult.language ("Portuguese (BR) · Dub"). */
    fun normalisedLabel(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val info = detect(raw)
        val lang = info.label() ?: return null
        val suffix = when (info.kind) {
            AudioKind.DUB -> "Dub"
            AudioKind.SUB -> "Sub"
            AudioKind.ORIGINAL -> "Original"
            else -> null
        }
        return if (suffix != null) "$lang · $suffix" else lang
    }
}
