@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.SwitchAccount
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nuvio.tv.domain.model.UserProfile
import com.nuvio.tv.ui.components.ProfileAvatarCircle
import com.nuvio.tv.ui.screens.profile.EditProfileOverlay
import com.nuvio.tv.ui.screens.profile.ProfileSelectionViewModel
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import com.nuvio.tv.ui.screens.account.AccountSignOutConfirmationDialog
import com.nuvio.tv.ui.theme.NuvioPrimitives
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.tv.core.qr.QrCodeGenerator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.core.cinemaos.account.CinemaOsAccountManager
import com.nuvio.tv.core.cinemaos.account.CinemaOsSyncService
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CinemaOsAccountUiState(
    val code: CinemaOsAccountManager.DeviceCode? = null,
    val secondsLeft: Int = 0,
    val error: String? = null
)

@HiltViewModel
class CinemaOsAccountViewModel @Inject constructor(
    private val manager: CinemaOsAccountManager,
    private val sync: CinemaOsSyncService
) : ViewModel() {
    val session = manager.session
    val lastSyncedAt = sync.lastSyncedAt
    val isSyncing = sync.isSyncing
    fun syncNow() { sync.pullNow() }
    private val _ui = MutableStateFlow(CinemaOsAccountUiState())
    val ui: StateFlow<CinemaOsAccountUiState> = _ui.asStateFlow()
    private var job: Job? = null

    /** Runs the QR device flow: shows a code, polls, auto-refreshes on expiry. Stops when signed in. */
    fun startFlow() {
        if (job?.isActive == true || manager.isSignedIn) return
        job = viewModelScope.launch {
            while (isActive && !manager.isSignedIn) {
                val code = manager.startDeviceLogin().getOrNull()
                if (code == null) {
                    _ui.value = CinemaOsAccountUiState(error = "Cannot reach cinemaos.tech. Retrying...")
                    delay(5_000)
                    continue
                }
                val deadline = System.currentTimeMillis() + code.expiresInSec * 1000L
                var interval = code.intervalSec
                _ui.value = CinemaOsAccountUiState(code = code, secondsLeft = code.expiresInSec)
                var elapsedSincePoll = 0
                var done = false
                while (isActive && !done) {
                    val left = ((deadline - System.currentTimeMillis()) / 1000).toInt()
                    if (left <= 0) break
                    _ui.update { it.copy(secondsLeft = left) }
                    delay(1_000)
                    elapsedSincePoll++
                    if (elapsedSincePoll < interval) continue
                    elapsedSincePoll = 0
                    val r = manager.pollDeviceLogin(code.deviceCode).getOrNull() ?: continue
                    when (r) {
                        is CinemaOsAccountManager.PollResult.Approved -> {
                            manager.completeSignIn(r.customToken)
                                .onSuccess { sync.pullNow(); _ui.value = CinemaOsAccountUiState() }
                                .onFailure { _ui.value = CinemaOsAccountUiState(error = "Sign-in failed. Try again.") }
                            done = true
                        }
                        CinemaOsAccountManager.PollResult.SlowDown -> interval += 5
                        CinemaOsAccountManager.PollResult.Expired -> break
                        CinemaOsAccountManager.PollResult.Denied -> {
                            _ui.value = CinemaOsAccountUiState(error = "Sign-in was denied on the website.")
                            delay(3_000)
                            break
                        }
                        CinemaOsAccountManager.PollResult.Pending -> Unit
                    }
                }
                if (done) return@launch
            }
        }
    }

    fun stopFlow() {
        job?.cancel()
        job = null
    }

    fun signOut() {
        manager.signOut()
        _ui.value = CinemaOsAccountUiState()
        startFlow()
    }

    override fun onCleared() {
        job?.cancel()
    }
}

@Composable
internal fun CinemaOsAccountSettingsContent(
    initialFocusRequester: FocusRequester?,
    onNavigateToManageProfiles: () -> Unit = {}
) {
    val viewModel: CinemaOsAccountViewModel = hiltViewModel()
    val profileViewModel: ProfileSelectionViewModel = hiltViewModel()
    val profiles by profileViewModel.profiles.collectAsStateWithLifecycle()
    val activeProfileId by profileViewModel.activeProfileId.collectAsStateWithLifecycle()
    val avatarCatalog by profileViewModel.avatarCatalog.collectAsStateWithLifecycle()
    val profileBackgroundCatalog by profileViewModel.profileBackgroundCatalog.collectAsStateWithLifecycle()
    val hasProfileBackgroundAccess by profileViewModel.hasProfileBackgroundAccess.collectAsStateWithLifecycle()
    val isSavingProfile by profileViewModel.isSaving.collectAsStateWithLifecycle()
    val activeProfile = profiles.firstOrNull { it.id == activeProfileId } ?: profiles.firstOrNull()
    var showProfileEditor by remember { mutableStateOf(false) }
    val editProfileRequester = initialFocusRequester ?: remember { FocusRequester() }
    var editorWasShown by remember { mutableStateOf(false) }
    LaunchedEffect(showProfileEditor) {
        if (showProfileEditor) {
            editorWasShown = true
        } else if (editorWasShown) {
            // Give focus back to Edit profile once the editor dialog has closed.
            kotlinx.coroutines.delay(150)
            runCatching { editProfileRequester.requestFocus() }
        }
    }
    val session by viewModel.session.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val lastSynced by viewModel.lastSyncedAt.collectAsStateWithLifecycle()
    val syncing by viewModel.isSyncing.collectAsStateWithLifecycle()
    val signedInUser = session
    val signedIn = signedInUser != null
    var showSignOutConfirmation by remember { mutableStateOf(false) }
    DisposableEffect(signedIn) {
        if (!signedIn) viewModel.startFlow()
        onDispose { viewModel.stopFlow() }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        SettingsDetailHeader(
            title = "CinemaOS Account",
            subtitle = if (signedInUser != null) {
                "Your profile, and sync of Continue Watching and your Library with cinemaos.tech"
            } else {
                "Sign in to sync Continue Watching and your Library with cinemaos.tech"
            }
        )
        if (signedInUser == null) {
            CinemaOsSignedOutPanel(
                ui = ui,
                onNewCode = { viewModel.stopFlow(); viewModel.startFlow() },
                newCodeFocusRequester = initialFocusRequester,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )
        } else SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
        ) {
            val current = signedInUser
            if (activeProfile != null) {
                CinemaOsProfileHeader(
                    profile = activeProfile,
                    avatarImageUrl = remember(activeProfile, avatarCatalog) {
                        activeProfile.avatarUrl?.takeIf { it.isNotBlank() }
                            ?: profileViewModel.getAvatarImageUrl(activeProfile.avatarId)
                    },
                    email = current?.email,
                    signedIn = current != null
                )
                SettingsActionRow(
                    title = "Edit profile",
                    subtitle = "Change your name and avatar",
                    leadingIcon = Icons.Default.Edit,
                    onClick = { showProfileEditor = true },
                    modifier = Modifier.focusRequester(editProfileRequester)
                )
                if (profiles.size > 1) {
                    SettingsActionRow(
                        title = "Switch profile",
                        subtitle = "Go back to Who's watching",
                        leadingIcon = Icons.Default.SwitchAccount,
                        onClick = { com.nuvio.tv.core.profile.ProfileSwitchRequests.request() }
                    )
                }
                SettingsActionRow(
                    title = "Manage profiles",
                    subtitle = "Add or remove profiles, and set a PIN",
                    leadingIcon = Icons.Default.People,
                    onClick = onNavigateToManageProfiles
                )
            }
            if (current != null) {
                SettingsActionRow(
                    title = if (syncing) "Syncing..." else "Sync now",
                    subtitle = if (lastSynced > 0L) {
                        "Last synced " + android.text.format.DateUtils.getRelativeTimeSpanString(
                            lastSynced, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS
                        )
                    } else "Last synced: never",
                    onClick = { if (!syncing) viewModel.syncNow() }
                )
                Button(
                    onClick = { showSignOutConfirmation = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioPrimitives.red600.copy(alpha = 0.2f),
                        focusedContainerColor = NuvioPrimitives.red600.copy(alpha = 0.4f),
                        contentColor = Color(0xFFF44336),
                        focusedContentColor = Color(0xFFF44336)
                    ),
                    shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = NuvioTheme.spacing.xs),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Logout,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
                        Text(
                            text = "Sign out",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
            ui.error?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.Error)
            }
        }
    }

    if (showSignOutConfirmation) {
        AccountSignOutConfirmationDialog(
            onConfirm = {
                viewModel.signOut()
                showSignOutConfirmation = false
            },
            onDismiss = { showSignOutConfirmation = false }
        )
    }

    val profileToEdit = activeProfile
    if (showProfileEditor && profileToEdit != null) {
        Dialog(
            onDismissRequest = { showProfileEditor = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            EditProfileOverlay(
                profile = profileToEdit,
                avatarCatalog = avatarCatalog,
                profileBackgroundCatalog = profileBackgroundCatalog,
                hasProfileBackgroundAccess = hasProfileBackgroundAccess,
                isSaving = isSavingProfile,
                avatarUrlResolver = { avatarId -> profileViewModel.getAvatarImageUrl(avatarId) },
                onDismiss = { showProfileEditor = false },
                onSaveProfile = { updated ->
                    profileViewModel.updateProfile(updated)
                    showProfileEditor = false
                }
            )
        }
    }
}

/**
 * Signed-out sign-in panel: a large QR on the left and short numbered steps, the code and the
 * URL on the right. Nothing profile-related is shown here, so the QR can stay big.
 */
@Composable
private fun CinemaOsSignedOutPanel(
    ui: CinemaOsAccountUiState,
    onNewCode: () -> Unit,
    newCodeFocusRequester: FocusRequester?,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier) {
        // Dominant QR: as large as the pane allows, up to 300dp (never below 220dp).
        val qrCard = (maxHeight - 8.dp).coerceIn(220.dp, 300.dp)
        val code = ui.code
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = qrCard),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xl),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(qrCard)
                    .clip(RoundedCornerShape(NuvioTheme.radii.lg))
                    .background(Color.White)
                    .padding(10.dp),
                contentAlignment = Alignment.Center
            ) {
                if (code != null) {
                    val qr = remember(code.verificationUrlComplete) {
                        QrCodeGenerator.generate(code.verificationUrlComplete, size = 768).asImageBitmap()
                    }
                    Image(
                        bitmap = qr,
                        contentDescription = "Sign-in QR code",
                        modifier = Modifier.fillMaxSize(),
                        filterQuality = FilterQuality.None
                    )
                } else {
                    Text(
                        text = if (ui.error != null) "No code" else "Getting a sign-in code...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF444444),
                        textAlign = TextAlign.Center
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
            ) {
                Text(
                    text = "Sign in to CinemaOS",
                    style = MaterialTheme.typography.headlineSmall,
                    color = NuvioTheme.colors.TextPrimary
                )
                Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                    SignInStep(1, "Scan the QR code with your phone")
                    SignInStep(2, "Or open cinemaos.tech/tv-login and type the code")
                    SignInStep(3, "Approve the sign-in on the website")
                }
                if (code != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)) {
                        Text(
                            text = code.userCode,
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 4.sp,
                            color = NuvioTheme.colors.TextPrimary,
                            maxLines = 1
                        )
                        Text(
                            text = code.verificationUrl.removePrefix("https://").removePrefix("http://") +
                                "  -  refreshes in ${ui.secondsLeft / 60}:${"%02d".format(ui.secondsLeft % 60)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioTheme.colors.TextSecondary,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }
                ui.error?.let {
                    Text(text = it, style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.Error)
                }
                SettingsActionRow(
                    title = "New code",
                    subtitle = null,
                    onClick = onNewCode,
                    modifier = if (newCodeFocusRequester != null) {
                        Modifier.focusRequester(newCodeFocusRequester)
                    } else {
                        Modifier
                    }
                )
            }
        }
    }
}

@Composable
private fun SignInStep(number: Int, text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(NuvioTheme.colors.Secondary.copy(alpha = 0.22f))
                .border(1.dp, NuvioTheme.colors.Secondary, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = NuvioTheme.colors.TextPrimary,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary
        )
    }
}

@Composable
private fun CinemaOsProfileHeader(
    profile: UserProfile,
    avatarImageUrl: String?,
    email: String?,
    signedIn: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = NuvioTheme.spacing.md,
                vertical = NuvioTheme.spacing.md
            ),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ProfileAvatarCircle(
            name = profile.name,
            colorHex = profile.avatarColorHex,
            size = 96.dp,
            avatarImageUrl = avatarImageUrl
        )
        Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)) {
            Text(
                text = profile.name,
                style = MaterialTheme.typography.headlineSmall,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Text(
                text = if (signedIn) (email ?: "Signed in to CinemaOS") else "Not signed in to CinemaOS",
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
    }
}
