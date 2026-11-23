package com.nuvio.tv.updater

import android.os.Build
import com.nuvio.tv.data.remote.dto.GitHubAssetDto

internal object AbiSelector {

    private val knownAbis = listOf(
        "arm64-v8a",
        "armeabi-v7a",
        "x86_64",
        "x86"
    )

    /**
     * Picks exactly one APK, the same one every time: the universal build (it runs on any device, and is what earlier
     * versions of the app downloaded), preferring the versioned name over the fixed-name copy a release also carries.
     * Without a universal build, the best ABI match, then anything that names no ABI.
     */
    fun chooseBestApkAsset(assets: List<GitHubAssetDto>): GitHubAssetDto? {
        // Sorted so the choice never depends on the order GitHub lists the assets in.
        val apkAssets = assets
            .filter { it.name.endsWith(".apk", ignoreCase = true) }
            .sortedBy { it.name.lowercase() }
        if (apkAssets.isEmpty()) return null
        if (apkAssets.size == 1) return apkAssets.first()

        val universal = apkAssets.filter { it.name.contains("universal", ignoreCase = true) }
        if (universal.isNotEmpty()) {
            // "CinemaOS-TV-1.2.3-universal.apk" carries the version; "Cinemaos-TV-Universal.apk" never does.
            return universal.firstOrNull { asset -> asset.name.any(Char::isDigit) } ?: universal.first()
        }

        val supported = Build.SUPPORTED_ABIS?.toList().orEmpty()

        // Prefer exact ABI match (in device preference order)
        for (abi in supported) {
            val candidate = apkAssets.firstOrNull { it.name.contains(abi, ignoreCase = true) }
            if (candidate != null) return candidate
        }

        // If we can at least avoid wrong-ABI picks, prefer APKs that don't mention a known ABI.
        val noAbiMention = apkAssets.firstOrNull { asset ->
            knownAbis.none { abi -> asset.name.contains(abi, ignoreCase = true) }
        }
        return noAbiMention ?: apkAssets.first()
    }
}
