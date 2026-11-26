package com.nuvio.tv.ui.screens.detail

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Formats an ISO-ish release / air date ("2026-09-25", "2026-09-25T00:00:00.000Z") as "Sep 25, 2026".
 * Returns null when [raw] holds no full date, so callers can fall back to the year.
 */
internal fun formatFullReleaseDate(raw: String?, locale: Locale = Locale.getDefault()): String? {
    val text = raw?.trim().orEmpty()
    if (text.length < 10) return null
    val date = runCatching { OffsetDateTime.parse(text).atZoneSameInstant(ZoneId.of("UTC")).toLocalDate() }.getOrNull()
        ?: runCatching { LocalDate.parse(text.substring(0, 10)) }.getOrNull()
        ?: return null
    return DateTimeFormatter.ofPattern("MMM d, yyyy", locale).format(date)
}
