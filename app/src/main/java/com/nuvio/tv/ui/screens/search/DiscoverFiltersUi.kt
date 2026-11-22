package com.nuvio.tv.ui.screens.search

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.contentTextDirection
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/** Value of the "any" row of a filter picker; never sent to the API. */
internal const val DISCOVER_ANY_VALUE = "__default__"

internal fun discoverFilterPickerId(key: DiscoverFilterKey): String = "filter:${key.name}"

@Composable
private fun discoverFilterTitle(key: DiscoverFilterKey): String = stringResource(
    when (key) {
        DiscoverFilterKey.SORT -> R.string.discover_filter_sort
        DiscoverFilterKey.GENRE -> R.string.discover_filter_genre
        DiscoverFilterKey.YEAR -> R.string.discover_filter_year
        DiscoverFilterKey.RATING -> R.string.discover_filter_rating
        DiscoverFilterKey.RUNTIME -> R.string.discover_filter_runtime
        DiscoverFilterKey.LANGUAGE -> R.string.discover_filter_language
        DiscoverFilterKey.COUNTRY -> R.string.discover_filter_country
        DiscoverFilterKey.PROVIDER -> R.string.discover_filter_provider
        DiscoverFilterKey.NETWORK -> R.string.discover_filter_network
        DiscoverFilterKey.FORMAT -> R.string.discover_filter_format
        DiscoverFilterKey.SEASON -> R.string.discover_filter_season
        DiscoverFilterKey.STATUS -> R.string.discover_filter_status
    }
)

private fun anyLabel(context: Context, key: DiscoverFilterKey): String = context.getString(
    when (key) {
        DiscoverFilterKey.YEAR -> R.string.discover_filter_any_year
        DiscoverFilterKey.RATING -> R.string.discover_filter_any_rating
        DiscoverFilterKey.RUNTIME -> R.string.discover_filter_any_runtime
        DiscoverFilterKey.LANGUAGE -> R.string.discover_filter_any_language
        DiscoverFilterKey.COUNTRY -> R.string.discover_filter_any_country
        DiscoverFilterKey.PROVIDER -> R.string.discover_filter_any_provider
        DiscoverFilterKey.NETWORK -> R.string.discover_filter_any_network
        DiscoverFilterKey.SEASON -> R.string.discover_filter_any_season
        DiscoverFilterKey.STATUS -> R.string.discover_filter_any_status
        DiscoverFilterKey.SORT, DiscoverFilterKey.GENRE, DiscoverFilterKey.FORMAT ->
            R.string.discover_genre_default
    }
)

private fun sortLabel(context: Context, sort: String, type: String): String {
    val tv = isTvLikeDiscoverType(type)
    return context.getString(
        when (sort) {
            DiscoverSort.NEWEST -> R.string.discover_sort_newest
            DiscoverSort.OLDEST -> R.string.discover_sort_oldest
            DiscoverSort.TOP_RATED -> R.string.discover_sort_top_rated
            DiscoverSort.MOST_VOTED -> R.string.discover_sort_most_voted
            DiscoverSort.REVENUE -> R.string.discover_sort_revenue
            DiscoverSort.UPCOMING ->
                if (tv) R.string.discover_sort_upcoming_tv else R.string.discover_sort_upcoming_movie
            DiscoverSort.NOW_PLAYING ->
                if (tv) R.string.discover_sort_now_playing_tv else R.string.discover_sort_now_playing_movie
            else -> R.string.discover_sort_popular
        }
    )
}

private fun ratingLabel(context: Context, value: String): String = context.getString(
    when (value) {
        "9" -> R.string.discover_rating_9
        "8" -> R.string.discover_rating_8
        "7" -> R.string.discover_rating_7
        "6" -> R.string.discover_rating_6
        else -> R.string.discover_rating_5
    }
)

private fun runtimeLabel(context: Context, value: String): String = context.getString(
    when (value) {
        "0-30" -> R.string.discover_runtime_under_30
        "30-60" -> R.string.discover_runtime_30_60
        "60-90" -> R.string.discover_runtime_60_90
        "90-120" -> R.string.discover_runtime_90_120
        else -> R.string.discover_runtime_over_120
    }
)

private fun formatLabel(context: Context, value: String): String = context.getString(
    if (value == ANIME_FORMAT_MOVIE) R.string.discover_format_movie else R.string.discover_format_tv
)

private fun seasonLabel(context: Context, value: String): String = context.getString(
    when (value) {
        "winter" -> R.string.discover_season_winter
        "spring" -> R.string.discover_season_spring
        "summer" -> R.string.discover_season_summer
        else -> R.string.discover_season_fall
    }
)

private fun statusLabel(context: Context, value: String): String = context.getString(
    if (value == ANIME_STATUS_AIRING) R.string.discover_status_airing else R.string.discover_status_ended
)

private fun languageLabel(code: String): String =
    Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault())
        .takeIf { it.isNotBlank() }
        ?.replaceFirstChar { it.uppercase() }
        ?: code.uppercase()

private fun countryLabel(code: String): String =
    Locale.Builder().setRegion(code).build().getDisplayCountry(Locale.getDefault())
        .takeIf { it.isNotBlank() } ?: code

/** Options of one extra filter picker, "any" row first (except Sort, Format and required addon years). */
private fun discoverFilterOptions(
    context: Context,
    key: DiscoverFilterKey,
    catalog: DiscoverCatalog
): List<DiscoverOption> {
    val any = DiscoverOption(anyLabel(context, key), DISCOVER_ANY_VALUE)
    if (!catalog.isTmdbDiscover) {
        // Addon year picker (Cinemeta "New"): the options are the addon's own years.
        val years = catalog.years.map { DiscoverOption(it, it) }
        return if (catalog.genreExtraRequired) years else listOf(any) + years
    }
    return when (key) {
        DiscoverFilterKey.SORT -> DiscoverSort.valuesFor(catalog.type)
            .map { DiscoverOption(sortLabel(context, it, catalog.type), it) }
        DiscoverFilterKey.YEAR -> buildList {
            add(any)
            DiscoverFilterValues.decades().forEach {
                add(
                    DiscoverOption(
                        context.getString(R.string.discover_filter_decade_format, it),
                        "$DISCOVER_DECADE_PREFIX$it"
                    )
                )
            }
            DiscoverFilterValues.years().forEach { add(DiscoverOption(it.toString(), it.toString())) }
        }
        DiscoverFilterKey.RATING ->
            listOf(any) + DiscoverFilterValues.ratings.map { DiscoverOption(ratingLabel(context, it), it) }
        DiscoverFilterKey.RUNTIME ->
            listOf(any) + DiscoverFilterValues.runtimes.map { DiscoverOption(runtimeLabel(context, it), it) }
        DiscoverFilterKey.LANGUAGE ->
            listOf(any) + DiscoverFilterValues.languages.map { DiscoverOption(languageLabel(it), it) }
        DiscoverFilterKey.COUNTRY ->
            listOf(any) + DiscoverFilterValues.countries.map { DiscoverOption(countryLabel(it), it) }
        DiscoverFilterKey.PROVIDER ->
            listOf(any) + DiscoverFilterValues.providers.map { (id, name) -> DiscoverOption(name, id) }
        DiscoverFilterKey.NETWORK ->
            listOf(any) + DiscoverFilterValues.networks.map { (id, name) -> DiscoverOption(name, id) }
        DiscoverFilterKey.FORMAT ->
            DiscoverFilterValues.animeFormats.map { DiscoverOption(formatLabel(context, it), it) }
        DiscoverFilterKey.SEASON ->
            listOf(any) + DiscoverFilterValues.animeSeasons.map { DiscoverOption(seasonLabel(context, it), it) }
        DiscoverFilterKey.STATUS ->
            listOf(any) + DiscoverFilterValues.animeStatuses.map { DiscoverOption(statusLabel(context, it), it) }
        DiscoverFilterKey.GENRE -> listOf(any)
    }
}

/** Current value of an extra filter for the selected catalog, or null for "any". */
private fun currentFilterValue(uiState: SearchUiState, catalog: DiscoverCatalog, key: DiscoverFilterKey): String? =
    if (catalog.isTmdbDiscover) {
        uiState.discoverTmdbFilters.value(key)
    } else if (key == DiscoverFilterKey.YEAR) {
        uiState.selectedDiscoverYear
    } else {
        null
    }


/** One pill of the filter row: a title, the current value and the options its picker offers. */
@Immutable
internal data class DiscoverPillSpec(
    val id: String,
    val title: String,
    val valueLabel: String,
    val active: Boolean,
    val options: List<DiscoverOption>,
    val selectedValue: String?,
    val onSelect: (DiscoverOption) -> Unit
)

/** Pills for the extra filters (sort, year, rating, anime format/season/status, ...). */
@Composable
internal fun discoverFilterPills(
    keys: List<DiscoverFilterKey>,
    uiState: SearchUiState,
    catalog: DiscoverCatalog,
    onSelectFilter: (DiscoverFilterKey, String?) -> Unit
): List<DiscoverPillSpec> {
    val context = LocalContext.current
    val anyValueLabel = stringResource(R.string.discover_filter_value_any)
    return keys.map { key ->
        val options = discoverFilterOptions(context, key, catalog)
        val current = currentFilterValue(uiState, catalog, key)
        val selectedValue = current ?: when (key) {
            DiscoverFilterKey.SORT -> DiscoverSort.POPULAR
            DiscoverFilterKey.FORMAT -> ANIME_FORMAT_TV
            else -> DISCOVER_ANY_VALUE
        }
        val selectedLabel = options.firstOrNull { it.value == selectedValue }?.label
        val active = if (catalog.isTmdbDiscover) {
            uiState.discoverTmdbFilters.isActive(key)
        } else {
            current != null && !catalog.genreExtraRequired
        }
        DiscoverPillSpec(
            id = discoverFilterPickerId(key),
            title = discoverFilterTitle(key),
            valueLabel = if (selectedValue == DISCOVER_ANY_VALUE) anyValueLabel else selectedLabel ?: anyValueLabel,
            active = active,
            options = options,
            selectedValue = selectedValue,
            onSelect = { option ->
                onSelectFilter(key, option.value.takeUnless { it == DISCOVER_ANY_VALUE })
            }
        )
    }
}

/** Visual weight of a [DiscoverChip]. */
internal enum class DiscoverChipStyle {
    /** Filled pill with an outline (filters, type chips). */
    Filled,

    /** Text only until focused (e.g. "Clear all"). */
    Quiet
}

/**
 * Pill used by every Discover chip: a subtle filled container with a hairline outline, a
 * white-fill / dark-text focused state (same as the Settings top bar tabs) and an optional
 * dropdown chevron.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun DiscoverChip(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    chevron: Boolean = false,
    chevronUp: Boolean = false,
    style: DiscoverChipStyle = DiscoverChipStyle.Filled
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(percent = 50)
    val quiet = style == DiscoverChipStyle.Quiet
    val container = when {
        quiet -> Color.Transparent
        selected -> NuvioTheme.colors.Secondary.copy(alpha = 0.20f)
        else -> Color.White.copy(alpha = 0.08f)
    }
    val outline = when {
        quiet -> Color.Transparent
        selected -> NuvioTheme.colors.Secondary.copy(alpha = 0.45f)
        else -> Color.White.copy(alpha = 0.12f)
    }
    val contentColor = when {
        focused -> NuvioTheme.colors.OnSecondary
        selected -> NuvioTheme.colors.TextPrimary
        quiet -> NuvioTheme.colors.TextTertiary
        else -> NuvioTheme.colors.TextSecondary
    }
    Card(
        onClick = onClick,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .semantics { role = Role.Tab; this.selected = selected },
        colors = CardDefaults.colors(
            containerColor = container,
            focusedContainerColor = NuvioTheme.colors.Secondary
        ),
        border = CardDefaults.border(
            border = Border(BorderStroke(1.dp, outline), shape = shape),
            focusedBorder = Border(BorderStroke(1.dp, NuvioTheme.colors.Secondary), shape = shape)
        ),
        shape = CardDefaults.shape(shape),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Medium
                ),
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (chevron) {
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = if (chevronUp) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * Compact "Label: value" chip (a [DiscoverChip] with a dropdown chevron)
 * that opens its option list in a popup overlay. When the popup closes focus goes back to this chip.
 */
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun DiscoverFilterPill(
    spec: DiscoverPillSpec,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    focusRequester: FocusRequester,
    upRequester: FocusRequester?,
    downRequester: FocusRequester?,
    blockFocus: Boolean,
    atEnd: Boolean,
    modifier: Modifier = Modifier
) {
    var wasExpanded by remember { mutableStateOf(false) }
    // Seed focused option with the current selection so reopen highlights the right row.
    var focusedOptionValue by remember(expanded) {
        mutableStateOf(if (expanded) spec.selectedValue else null)
    }
    val selectedItemFocusRequester = remember { FocusRequester() }
    val selectedBringIntoViewRequester = remember { BringIntoViewRequester() }

    // Popup content attaches focus targets a few frames after expand.
    LaunchedEffect(expanded, spec.selectedValue) {
        if (!expanded || spec.selectedValue == null) return@LaunchedEffect
        var focused = selectedItemFocusRequester.requestFocusAfterFrames(frames = 3)
        var attempt = 0
        while (!focused && attempt < 6) {
            delay(32)
            focused = runCatching { selectedItemFocusRequester.requestFocus() }.getOrDefault(false)
            attempt++
        }
        if (!focused) return@LaunchedEffect
        runCatching { selectedBringIntoViewRequester.bringIntoView() }
        // The menu may still move initial focus to the first item once the popup settles.
        delay(48)
        if (runCatching { selectedItemFocusRequester.requestFocus() }.getOrDefault(false)) {
            runCatching { selectedBringIntoViewRequester.bringIntoView() }
        }
    }

    // Closing the popup (selection or Back) hands focus back to the pill that opened it.
    LaunchedEffect(expanded) {
        if (wasExpanded && !expanded) {
            focusRequester.requestFocusAfterFrames(frames = 2)
        }
        wasExpanded = expanded
    }

    Box(modifier = modifier) {
        DiscoverChip(
            title = "${spec.title}: ${spec.valueLabel}",
            selected = spec.active,
            onClick = { onExpandedChange(!expanded) },
            chevron = true,
            chevronUp = expanded,
            modifier = Modifier
                .widthIn(max = 340.dp)
                .focusRequester(focusRequester)
                .discoverChipFocus(upRequester, downRequester, blockFocus, atEnd)
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                focusedOptionValue = null
                onExpandedChange(false)
            },
            modifier = Modifier
                .widthIn(min = 240.dp)
                .heightIn(max = 300.dp),
            shape = RoundedCornerShape(14.dp),
            containerColor = NuvioTheme.colors.BackgroundCard,
            tonalElevation = NuvioTheme.spacing.none,
            shadowElevation = NuvioTheme.spacing.sm,
            border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border)
        ) {
            spec.options.forEach { option ->
                val isSelected = option.value == spec.selectedValue
                val isOptionFocused = option.value == focusedOptionValue
                val itemTextColor = if (isOptionFocused) NuvioTheme.colors.OnSecondary else NuvioTheme.colors.TextPrimary
                val itemBackgroundColor = when {
                    isOptionFocused -> NuvioTheme.colors.Secondary
                    isSelected -> NuvioTheme.colors.FocusBackground
                    else -> Color.Transparent
                }

                DropdownMenuItem(
                    modifier = Modifier
                        .then(
                            if (isSelected) {
                                Modifier
                                    .focusRequester(selectedItemFocusRequester)
                                    .bringIntoViewRequester(selectedBringIntoViewRequester)
                            } else {
                                Modifier
                            }
                        )
                        .padding(horizontal = 6.dp, vertical = NuvioTheme.spacing.xxs)
                        .background(
                            color = itemBackgroundColor,
                            shape = RoundedCornerShape(10.dp)
                        )
                        .onFocusChanged { state ->
                            val hasFocus = state.isFocused || state.hasFocus
                            focusedOptionValue = when {
                                hasFocus -> option.value
                                focusedOptionValue == option.value -> null
                                else -> focusedOptionValue
                            }
                        },
                    text = {
                        Text(
                            text = option.label,
                            color = itemTextColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = TextStyle(
                                textDirection = option.label.contentTextDirection()
                            )
                        )
                    },
                    onClick = { spec.onSelect(option) },
                    colors = MenuDefaults.itemColors(
                        textColor = itemTextColor,
                        disabledTextColor = NuvioTheme.colors.TextDisabled
                    )
                )
            }
        }
    }
}

/**
 * Shared D-pad wiring of every chip in a chips row: Up / Down go to the neighbouring row, Right
 * stops at the last chip. Left is left alone so it can reach the sidebar / nav.
 */
private fun Modifier.discoverChipFocus(
    upRequester: FocusRequester?,
    downRequester: FocusRequester?,
    blockFocus: Boolean,
    atEnd: Boolean
): Modifier = focusProperties {
    if (blockFocus) canFocus = false
    if (upRequester != null) up = upRequester
    if (downRequester != null) down = downRequester
    if (atEnd) right = FocusRequester.Cancel
}

/**
 * The single-line, horizontally scrolling row of essential filter pills, then the "More filters"
 * chip (the rarer [morePills] follow it once expanded) and "Clear all" as the very last chip.
 *
 * D-pad: [firstChipRequester] is attached to the first chip, so the type chips' Down lands there;
 * [rowRequester] enters the row as a group and restores the last focused chip (used by Up from the
 * grid). Up leaves to [upRequester], Down to [downRequester] (first result), Right stops at the
 * last chip; Left is left to the default search (sidebar / nav).
 */
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
internal fun DiscoverFilterPillRow(
    pills: List<DiscoverPillSpec>,
    morePills: List<DiscoverPillSpec>,
    moreExpanded: Boolean,
    onMoreExpandedChange: (Boolean) -> Unit,
    expandedPicker: String?,
    onExpandedChange: (String?) -> Unit,
    onReset: () -> Unit,
    rowRequester: FocusRequester,
    firstChipRequester: FocusRequester,
    upRequester: FocusRequester?,
    downRequester: FocusRequester?,
    blockFocus: Boolean,
    modifier: Modifier = Modifier
) {
    val chipRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val firstId = pills.firstOrNull()?.id
        ?: if (morePills.isNotEmpty()) DISCOVER_MORE_CHIP_ID else DISCOVER_CLEAR_PILL_ID
    fun requesterFor(id: String): FocusRequester =
        if (id == firstId) firstChipRequester else chipRequesters.getOrPut(id) { FocusRequester() }
    val moreActiveCount = morePills.count { it.active }
    val moreTitle = when {
        moreExpanded -> DISCOVER_FEWER_FILTERS_LABEL
        moreActiveCount > 0 -> "$DISCOVER_MORE_FILTERS_LABEL ($moreActiveCount)"
        else -> DISCOVER_MORE_FILTERS_LABEL
    }
    // "Clear all" only matters while a filter is set; with nothing else to focus it stays as the
    // row's only chip so the type chips' Down still has a target.
    val anyActive = pills.any { it.active } || morePills.any { it.active }
    val showClear = anyActive || (pills.isEmpty() && morePills.isEmpty())
    val lastId = when {
        showClear -> DISCOVER_CLEAR_PILL_ID
        morePills.isNotEmpty() && moreExpanded -> morePills.last().id
        morePills.isNotEmpty() -> DISCOVER_MORE_CHIP_ID
        else -> pills.last().id
    }
    val scope = rememberCoroutineScope()

    @Composable
    fun Pill(spec: DiscoverPillSpec) {
        DiscoverFilterPill(
            spec = spec.copy(
                onSelect = { option ->
                    spec.onSelect(option)
                    onExpandedChange(null)
                }
            ),
            expanded = expandedPicker == spec.id,
            onExpandedChange = { shouldExpand -> onExpandedChange(if (shouldExpand) spec.id else null) },
            focusRequester = requesterFor(spec.id),
            upRequester = upRequester,
            downRequester = downRequester,
            blockFocus = blockFocus,
            atEnd = spec.id == lastId
        )
    }

    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(rowRequester)
            .focusRestorer { requesterFor(firstId) },
        contentPadding = PaddingValues(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(items = pills, key = { it.id }) { spec -> Pill(spec) }
        if (morePills.isNotEmpty()) {
            item(key = DISCOVER_MORE_CHIP_ID) {
                DiscoverChip(
                    title = moreTitle,
                    selected = moreActiveCount > 0,
                    onClick = {
                        onExpandedChange(null)
                        onMoreExpandedChange(!moreExpanded)
                    },
                    chevron = true,
                    chevronUp = moreExpanded,
                    modifier = Modifier
                        .focusRequester(requesterFor(DISCOVER_MORE_CHIP_ID))
                        .discoverChipFocus(
                            upRequester, downRequester, blockFocus,
                            atEnd = lastId == DISCOVER_MORE_CHIP_ID
                        )
                )
            }
            if (moreExpanded) {
                items(items = morePills, key = { it.id }) { spec -> Pill(spec) }
            }
        }
        if (showClear) {
            item(key = DISCOVER_CLEAR_PILL_ID) {
                DiscoverChip(
                    title = stringResource(R.string.discover_filters_clear_all),
                    style = DiscoverChipStyle.Quiet,
                    onClick = {
                        onReset()
                        // The chip disappears once nothing is active; keep focus in the row.
                        scope.launch { firstChipRequester.requestFocusAfterFrames(frames = 3) }
                    },
                    modifier = Modifier
                        .focusRequester(requesterFor(DISCOVER_CLEAR_PILL_ID))
                        .discoverChipFocus(upRequester, downRequester, blockFocus, atEnd = true)
                )
            }
        }
    }
}

private const val DISCOVER_CLEAR_PILL_ID = "clear_all"
private const val DISCOVER_MORE_CHIP_ID = "more_filters"
private const val DISCOVER_MORE_FILTERS_LABEL = "More filters"
private const val DISCOVER_FEWER_FILTERS_LABEL = "Fewer filters"

/**
 * Movies / Series / Anime switch built from [DiscoverChip]s. Every chip is focusable and the selected one is tinted. Down moves to
 * [downRequester] (the first filter chip); Up is left to the default search, which reaches the top
 * navigation bar; Right stops at the last chip.
 */
@Composable
internal fun DiscoverTypeSwitch(
    types: List<DiscoverOption>,
    selectedType: String,
    requesterFor: (String) -> FocusRequester,
    downRequester: FocusRequester,
    blockFocus: Boolean,
    onSelectType: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        types.forEachIndexed { index, type ->
            DiscoverChip(
                title = type.label,
                selected = type.value == selectedType,
                onClick = { onSelectType(type.value) },
                modifier = Modifier
                    .focusRequester(requesterFor(type.value))
                    .discoverChipFocus(
                        upRequester = null,
                        downRequester = downRequester,
                        blockFocus = blockFocus,
                        atEnd = index == types.lastIndex
                    )
            )
        }
    }
}
