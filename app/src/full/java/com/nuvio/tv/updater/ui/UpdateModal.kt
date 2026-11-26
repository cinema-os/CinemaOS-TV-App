@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.updater.ui

import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.updater.UpdateUiState
import com.nuvio.tv.updater.model.AppUpdate

private val GlassBg = Color(0xFF0C0D10)

/** Premium dark-glass OTA update modal: notes, Update now / Later, progress, installing. */
@Composable
internal fun UpdateModal(
    state: UpdateUiState,
    update: AppUpdate,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val primaryFocus = remember { FocusRequester() }
    // Downloading and installing are tracked by the view model, which starts the install itself once the download
    // finishes, so the modal only reflects them and survives being hidden for the unknown-sources prompt.
    val busy = state.isDownloading || state.isInstalling

    // Focus returns to the primary button whenever the buttons come back, e.g. after the user declines the install.
    LaunchedEffect(update.tag, busy) { if (!busy) primaryFocus.requestFocusAfterFrames() }

    val version = update.tag.let { if (it.startsWith("v")) it else "v$it" }
    val sizeLabel = update.assetSizeBytes?.let { Formatter.formatShortFileSize(context, it) }
    val notes = remember(update.notes) {
        update.notes.lines()
            .map { it.trim().removePrefix("-").removePrefix("*").trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .take(6)
    }

    Dialog(
        onDismissRequest = { if (!state.isDownloading) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = !state.isDownloading,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.72f)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .width(620.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(GlassBg.copy(alpha = 0.96f), Color(0xFF14161B).copy(alpha = 0.96f))
                        )
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(28.dp))
                    .padding(horizontal = 36.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    painter = painterResource(R.drawable.app_logo_wordmark),
                    contentDescription = null,
                    modifier = Modifier.height(34.dp),
                    contentScale = ContentScale.Fit
                )
                Spacer(Modifier.height(18.dp))
                Text(
                    text = "Update available · $version",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold
                )
                if (sizeLabel != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(text = sizeLabel, color = Color.White.copy(alpha = 0.5f), fontSize = 14.sp)
                }
                Spacer(Modifier.height(18.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 170.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.White.copy(alpha = 0.055f))
                        .padding(horizontal = 18.dp, vertical = 14.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "What's new",
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.2.sp
                    )
                    if (notes.isEmpty()) {
                        Text("Performance and stability improvements", color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp)
                    } else notes.forEach { line ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(
                                Modifier
                                    .padding(top = 8.dp)
                                    .size(5.dp)
                                    .background(Color.White.copy(alpha = 0.7f), RoundedCornerShape(50))
                            )
                            Text(
                                text = line,
                                color = Color.White.copy(alpha = 0.88f),
                                fontSize = 15.sp,
                                lineHeight = 21.sp,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                Spacer(Modifier.height(22.dp))

                when {
                    state.isQuestDevice -> {
                        Text(
                            text = "Update $version is available \u2014 download it from cinemaos.tech/apps",
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Button(
                                onClick = {
                                    runCatching {
                                        context.startActivity(
                                            android.content.Intent(
                                                android.content.Intent.ACTION_VIEW,
                                                android.net.Uri.parse("https://cinemaos.tech/apps")
                                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    }
                                },
                                modifier = Modifier.focusRequester(primaryFocus),
                                colors = ButtonDefaults.colors(
                                    containerColor = Color.White,
                                    focusedContainerColor = Color.White,
                                    contentColor = Color.Black,
                                    focusedContentColor = Color.Black
                                ),
                                shape = ButtonDefaults.shape(RoundedCornerShape(50)),
                                contentPadding = PaddingValues(horizontal = 36.dp, vertical = 12.dp)
                            ) { Text("Open cinemaos.tech/apps", fontWeight = FontWeight.SemiBold, fontSize = 17.sp) }
                            Button(
                                onClick = onDismiss,
                                shape = ButtonDefaults.shape(RoundedCornerShape(50)),
                                contentPadding = PaddingValues(horizontal = 32.dp, vertical = 12.dp)
                            ) { Text("Later", fontSize = 17.sp) }
                        }
                    }
                    state.isInstalling -> StatusBlock("Confirm the install when Android asks…", null)
                    state.isDownloading -> {
                        val p = state.downloadProgress
                        StatusBlock(
                            if (p != null) "Downloading ${(p * 100).toInt().coerceIn(0, 100)}%" else "Preparing download…",
                            p
                        )
                    }
                    else -> {
                        if (state.errorMessage != null) {
                            Text(
                                text = state.errorMessage,
                                color = Color(0xFFFF8A80),
                                fontSize = 14.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(12.dp))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Button(
                                onClick = { if (state.downloadedApkPath == null) onDownload() else onInstall() },
                                modifier = Modifier.focusRequester(primaryFocus),
                                colors = ButtonDefaults.colors(
                                    containerColor = Color.White,
                                    focusedContainerColor = Color.White,
                                    contentColor = Color.Black,
                                    focusedContentColor = Color.Black
                                ),
                                border = ButtonDefaults.border(
                                    focusedBorder = Border(BorderStroke(3.dp, Color.White.copy(alpha = 0.55f)), shape = RoundedCornerShape(50))
                                ),
                                scale = ButtonDefaults.scale(focusedScale = 1.06f),
                                shape = ButtonDefaults.shape(RoundedCornerShape(50)),
                                contentPadding = PaddingValues(horizontal = 36.dp, vertical = 12.dp)
                            ) {
                                Text(
                                    if (state.errorMessage != null) "Retry" else "Update now",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 17.sp
                                )
                            }
                            Button(
                                onClick = onDismiss,
                                colors = ButtonDefaults.colors(
                                    containerColor = Color.White.copy(alpha = 0.08f),
                                    focusedContainerColor = Color.White.copy(alpha = 0.20f),
                                    contentColor = Color.White.copy(alpha = 0.85f),
                                    focusedContentColor = Color.White
                                ),
                                border = ButtonDefaults.border(
                                    border = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)), shape = RoundedCornerShape(50)),
                                    focusedBorder = Border(BorderStroke(3.dp, Color.White.copy(alpha = 0.55f)), shape = RoundedCornerShape(50))
                                ),
                                scale = ButtonDefaults.scale(focusedScale = 1.06f),
                                shape = ButtonDefaults.shape(RoundedCornerShape(50)),
                                contentPadding = PaddingValues(horizontal = 32.dp, vertical = 12.dp)
                            ) {
                                Text("Later", fontWeight = FontWeight.Medium, fontSize = 17.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBlock(label: String, progress: Float?) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.12f))
        ) {
            if (progress != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .fillMaxSize()
                        .clip(RoundedCornerShape(50))
                        .background(Color.White)
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.35f)
                        .fillMaxSize()
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.6f))
                )
            }
        }
    }
}
