package com.nuvio.tv.updater

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.R
import com.nuvio.tv.updater.model.AppUpdate
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "UpdateInstall"
private const val DOWNLOAD_TIMEOUT_MS = 20 * 60_000L
private const val INSTALL_TIMEOUT_MS = 3 * 60_000L

data class UpdateUiState(
    val isChecking: Boolean = false,
    val update: AppUpdate? = null,
    val isUpdateAvailable: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgress: Float? = null,
    /** An install was handed to Android and is waiting for the user's confirmation. */
    val isInstalling: Boolean = false,
    val downloadedApkPath: String? = null,
    val showBanner: Boolean = false,
    val showUnknownSourcesDialog: Boolean = false,
    val errorMessage: String? = null,
    val feedbackMessage: String? = null,
    val updateBannerEnabled: Boolean = true,
    val updateChannel: UpdateChannel = UpdateChannel.STABLE,
    val autoInstallEnabled: Boolean = false,
    val autoInstallSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
    val autoInstallNoticeEnabled: Boolean = true,
    /** Updates the user installs carry dex metadata; off sends them through the system installer screen. */
    val optimizeDuringInstall: Boolean = true,
    val optimizeDuringInstallSupported: Boolean = ApkInstaller.supportsDexMetadata,
    /** Release installed automatically since the last launch, shown in the banner until dismissed. */
    val installedUpdate: AppUpdate? = null,
    /** Meta Quest cannot run the in-app install flow; updates there are downloaded from the website. */
    val isQuestDevice: Boolean = DeviceUtils.isMetaQuest()
)

@HiltViewModel
class UpdateViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val updateRepository: UpdateRepository,
    private val updatePreferences: UpdatePreferences,
    private val downloadStore: UpdateDownloadStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(UpdateUiState())
    val uiState: StateFlow<UpdateUiState> = _uiState.asStateFlow()
    private var updateCheckJob: Job? = null

    init {
        viewModelScope.launch {
            UpdateInstallEvents.outcomes.collect { onInstallOutcome(it) }
        }
        viewModelScope.launch {
            val enabled = updatePreferences.updateBannerEnabled.first()
            val channel = updatePreferences.getOrInitializeUpdateChannel()
            val autoInstallEnabled = updatePreferences.autoInstallEnabled.first()
            val autoInstallNoticeEnabled = updatePreferences.autoInstallNoticeEnabled.first()
            val optimizeDuringInstall = updatePreferences.optimizeDuringInstall.first()
            _uiState.update {
                it.copy(
                    updateBannerEnabled = enabled,
                    updateChannel = channel,
                    autoInstallEnabled = autoInstallEnabled,
                    autoInstallNoticeEnabled = autoInstallNoticeEnabled,
                    optimizeDuringInstall = optimizeDuringInstall
                )
            }
            downloadStore.clearIfInstalled()
            consumeAutoInstalledUpdate()
            syncBackgroundJobs()
            if (enabled) {
                checkForUpdates(force = false, showNoUpdateFeedback = false)
            }
        }
    }

    fun checkForUpdates(force: Boolean, showNoUpdateFeedback: Boolean) {
        if (!force && !_uiState.value.updateBannerEnabled) return

        updateCheckJob?.cancel()
        updateCheckJob = viewModelScope.launch {
            val channel = _uiState.value.updateChannel
            _uiState.update {
                it.copy(
                    isChecking = true,
                    showBanner = false,
                    showUnknownSourcesDialog = false,
                    errorMessage = null,
                    feedbackMessage = null
                )
            }

            val dismissedTag = updatePreferences.ignoredTag.first()
            val result = updateRepository.getLatestUpdate(channel)
            updatePreferences.setLastCheckAtMs(System.currentTimeMillis())

            result
                .onSuccess { update ->
                    val remoteNewer = VersionUtils.isRemoteNewer(update.tag, BuildConfig.VERSION_NAME)
                    val downloadedFile = if (remoteNewer) downloadStore.downloadedFileFor(update) else null
                    // The release just installed carries the notes the "updated" banner can show.
                    val installedUpdate = _uiState.value.installedUpdate?.let { installed ->
                        if (installed.tag == update.tag) update else installed
                    }
                    if (remoteNewer && downloadedFile == null && update.tag != dismissedTag) {
                        UpdateJobService.scheduleCheckNow(context)
                    }
                    val shouldShow = UpdateBannerPolicy.shouldShow(
                        isRemoteNewer = remoteNewer,
                        force = force,
                        bannerEnabled = _uiState.value.updateBannerEnabled,
                        dismissedTag = dismissedTag,
                        updateTag = update.tag
                    )

                    _uiState.update { state ->
                        state.copy(
                            isChecking = false,
                            update = update.takeIf { remoteNewer },
                            isUpdateAvailable = remoteNewer,
                            isDownloading = false,
                            downloadProgress = null,
                            downloadedApkPath = downloadedFile?.absolutePath
                                ?: state.downloadedApkPath.takeIf { remoteNewer && state.update?.tag == update.tag },
                            showBanner = shouldShow || installedUpdate != null,
                            installedUpdate = installedUpdate,
                            showUnknownSourcesDialog = false,
                            errorMessage = null,
                            feedbackMessage = if (showNoUpdateFeedback && !remoteNewer) {
                                noUpdateFeedback(channel)
                            } else {
                                null
                            }
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isChecking = false,
                            update = null,
                            isUpdateAvailable = false,
                            isDownloading = false,
                            downloadProgress = null,
                            downloadedApkPath = null,
                            showBanner = it.installedUpdate != null,
                            showUnknownSourcesDialog = false,
                            errorMessage = null,
                            feedbackMessage = if (showNoUpdateFeedback) {
                                if (error is NoEligibleUpdateException) {
                                    noUpdateFeedback(channel)
                                } else {
                                    error.message ?: context.getString(R.string.update_error_check_failed)
                                }
                            } else {
                                null
                            }
                        )
                    }
                }
        }
    }

    private fun noUpdateFeedback(channel: UpdateChannel): String =
        if (channel == UpdateChannel.STABLE && VersionUtils.isPrerelease(BuildConfig.VERSION_NAME)) {
            context.getString(R.string.update_waiting_for_stable)
        } else {
            context.getString(R.string.update_latest_version)
        }

    fun dismissBanner() {
        val state = _uiState.value
        _uiState.update {
            it.copy(
                showBanner = false,
                isInstalling = false,
                showUnknownSourcesDialog = false,
                errorMessage = null,
                installedUpdate = null
            )
        }
        val tag = state.update?.tag
        if (tag != null) {
            viewModelScope.launch {
                updatePreferences.setIgnoredTag(tag)
            }
        }
    }

    fun dismissUnknownSourcesDialog() {
        _uiState.update { it.copy(showUnknownSourcesDialog = false) }
    }

    fun consumeFeedbackMessage() {
        _uiState.update { it.copy(feedbackMessage = null) }
    }

    fun setUpdateBannerEnabled(enabled: Boolean) {
        val changed = _uiState.value.updateBannerEnabled != enabled
        _uiState.update {
            it.copy(
                updateBannerEnabled = enabled,
                showBanner = if (enabled) it.showBanner else false,
                showUnknownSourcesDialog = if (enabled) it.showUnknownSourcesDialog else false
            )
        }
        viewModelScope.launch {
            updatePreferences.setUpdateBannerEnabled(enabled)
            syncBackgroundJobs()
            if (enabled && changed) {
                checkForUpdates(force = false, showNoUpdateFeedback = false)
            }
        }
    }

    fun setUpdateChannel(channel: UpdateChannel) {
        val state = _uiState.value
        if (state.updateChannel == channel) return

        updateCheckJob?.cancel()
        UpdateJobService.cancelIdleInstall(context)
        _uiState.update {
            it.copy(
                updateChannel = channel,
                isChecking = false,
                update = null,
                isUpdateAvailable = false,
                isDownloading = false,
                downloadProgress = null,
                isInstalling = false,
                downloadedApkPath = null,
                showBanner = false,
                showUnknownSourcesDialog = false,
                errorMessage = null,
                feedbackMessage = null
            )
        }
        viewModelScope.launch {
            downloadStore.clear()
            updatePreferences.setUpdateChannel(channel)
            run {
                checkForUpdates(force = true, showNoUpdateFeedback = false)
            }
        }
    }

    fun downloadUpdate() {
        val state = _uiState.value
        val update = state.update ?: return
        if (state.isDownloading || state.isInstalling || state.isQuestDevice) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isDownloading = true,
                    downloadProgress = 0f,
                    errorMessage = null
                )
            }

            val result = withContext(Dispatchers.IO) {
                // A download that stalls or drags on must end in an error state instead of hanging forever.
                val timed = kotlinx.coroutines.withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) {
                    downloadStore.download(update) { downloaded, total ->
                        val progress = if (total != null && total > 0) {
                            (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                        } else {
                            null
                        }
                        _uiState.update { it.copy(downloadProgress = progress) }
                    }
                }
                timed ?: Result.failure(java.io.IOException(context.getString(R.string.update_error_download_failed)))
            }

            result
                .onSuccess { file ->
                    _uiState.update {
                        it.copy(
                            isDownloading = false,
                            downloadProgress = null,
                            downloadedApkPath = file.absolutePath,
                            errorMessage = null
                        )
                    }
                    installUpdateOrRequestPermission()
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isDownloading = false,
                            downloadProgress = null,
                            downloadedApkPath = null,
                            errorMessage = error.message ?: context.getString(R.string.update_error_download_failed),
                            showBanner = true
                        )
                    }
                }
        }
    }

    fun installUpdateOrRequestPermission() {
        val state = _uiState.value
        if (state.isQuestDevice) return
        val apkPath = state.downloadedApkPath ?: return
        val apkFile = File(apkPath)
        if (!apkFile.exists()) {
            _uiState.update {
                it.copy(
                    isInstalling = false,
                    errorMessage = context.getString(R.string.update_error_apk_missing),
                    showBanner = true
                )
            }
            return
        }

        if (!ApkInstaller.canRequestPackageInstalls(context)) {
            _uiState.update { it.copy(isInstalling = false, showUnknownSourcesDialog = true, showBanner = true) }
            return
        }

        // One install at a time: a second session for the same app would race the first one's confirmation screen.
        if (state.isInstalling) return
        _uiState.update { it.copy(isInstalling = true, showUnknownSourcesDialog = false, errorMessage = null) }
        // Without dex metadata a session install gains nothing over the system installer, whose screen stays up
        // while the app is closed for the install and offers to open it again.
        val useSystemInstaller = !ApkInstaller.supportsDexMetadata || !state.optimizeDuringInstall
        // Never leave the UI stuck on "installing": if no outcome arrives, show an error and allow a retry.
        viewModelScope.launch {
            kotlinx.coroutines.delay(INSTALL_TIMEOUT_MS)
            _uiState.update {
                if (it.isInstalling) {
                    it.copy(
                        isInstalling = false,
                        errorMessage = context.getString(R.string.update_error_install_failed),
                        showBanner = true
                    )
                } else it
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            // A started session reports back to UpdateInstallResultReceiver, which opens the system confirmation
            // screen and tells onInstallOutcome if the install ends without replacing the app.
            val sessionStarted = !useSystemInstaller &&
                runCatching { ApkInstaller.install(context, apkFile, background = false) }
                    .onFailure { Log.w(TAG, "Session install failed, falling back to the system installer", it) }
                    .isSuccess
            if (sessionStarted) return@launch

            val launched = runCatching { ApkInstaller.launchSystemInstaller(context, apkFile) }
                .onFailure { Log.w(TAG, "System installer failed", it) }
                .isSuccess
            _uiState.update {
                it.copy(
                    isInstalling = false,
                    errorMessage = if (launched) null else context.getString(R.string.update_error_install_failed),
                    showBanner = it.showBanner || !launched
                )
            }
        }
    }

    private fun onInstallOutcome(outcome: UpdateInstallOutcome) {
        _uiState.update { state ->
            when (outcome) {
                UpdateInstallOutcome.Ended -> state.copy(isInstalling = false)
                is UpdateInstallOutcome.Failed -> state.copy(
                    isInstalling = false,
                    errorMessage = context.getString(R.string.update_error_install_failed),
                    showBanner = true
                )
            }
        }
    }

    // Shows the "updated" banner once after the idle job installed a release, then forgets it.
    private suspend fun consumeAutoInstalledUpdate() {
        val tag = updatePreferences.autoInstalledTag.first() ?: return
        // Still older than the installed version means the install has not happened yet.
        if (VersionUtils.isRemoteNewer(tag, BuildConfig.VERSION_NAME)) return
        updatePreferences.setAutoInstalledTag(null)
        if (VersionUtils.isRemoteNewer(BuildConfig.VERSION_NAME, tag)) return
        if (!_uiState.value.autoInstallNoticeEnabled) return
        val installed = AppUpdate(
            tag = tag,
            title = tag,
            notes = "",
            releaseUrl = null,
            assetName = "",
            assetUrl = "",
            assetSizeBytes = null
        )
        _uiState.update { it.copy(installedUpdate = installed, showBanner = true) }
    }

    fun setAutoInstallNoticeEnabled(enabled: Boolean) {
        _uiState.update { it.copy(autoInstallNoticeEnabled = enabled) }
        viewModelScope.launch { updatePreferences.setAutoInstallNoticeEnabled(enabled) }
    }

    fun setOptimizeDuringInstall(enabled: Boolean) {
        _uiState.update { it.copy(optimizeDuringInstall = enabled) }
        viewModelScope.launch { updatePreferences.setOptimizeDuringInstall(enabled) }
    }

    fun setAutoInstallEnabled(enabled: Boolean) {
        _uiState.update { it.copy(autoInstallEnabled = enabled) }
        viewModelScope.launch {
            updatePreferences.setAutoInstallEnabled(enabled)
            syncBackgroundJobs()
            if (enabled && downloadStore.pendingInstall() != null) {
                UpdateJobService.scheduleIdleInstall(context)
            }
        }
    }

    // Background checks run while the banner or automatic installs are on; the idle install job only with the latter.
    private fun syncBackgroundJobs() {
        val state = _uiState.value
        if (state.updateBannerEnabled || state.autoInstallEnabled) {
            UpdateJobService.schedulePeriodicCheck(context)
        } else {
            UpdateJobService.cancelPeriodicCheck(context)
        }
        if (!state.autoInstallEnabled) UpdateJobService.cancelIdleInstall(context)
    }

    fun openUnknownSourcesSettings() {
        listOfNotNull(
            ApkInstaller.buildUnknownSourcesSettingsIntent(context),
            Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ).any { runCatching { context.startActivity(it) }.isSuccess }
    }
}

object DeviceUtils {
    fun isMetaQuest(): Boolean {
        val m = Build.MANUFACTURER.orEmpty()
        return m.equals("Oculus", true) || m.equals("Meta", true) ||
            Build.MODEL.orEmpty().contains("Quest", true)
    }
}
