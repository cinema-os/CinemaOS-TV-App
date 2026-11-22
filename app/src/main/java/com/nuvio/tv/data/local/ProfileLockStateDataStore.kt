package com.nuvio.tv.data.local

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton

private val Context.profileLockStateDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "profile_lock_state",
    corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler { androidx.datastore.preferences.core.emptyPreferences() }
)

/**
 * Profile PINs, stored on this device only.
 *
 * CinemaOS accounts have no profile/PIN backend (the upstream server RPCs need an account this
 * app never signs into), so the PIN is kept here as a salted PBKDF2 hash per profile. A profile is
 * locked exactly when it has a stored hash, so the lock flag can never point at a PIN that does
 * not exist on this device.
 */
@Singleton
class ProfileLockStateDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    sealed interface VerifyResult {
        data object Unlocked : VerifyResult
        data object NoPin : VerifyResult
        data object Wrong : VerifyResult
        data class TooManyAttempts(val retryAfterSeconds: Int) : VerifyResult
    }

    private val dataStore = context.profileLockStateDataStore
    private val failedAttempts = ConcurrentHashMap<Int, Int>()
    private val lockedUntilMs = ConcurrentHashMap<Int, Long>()

    val pinEnabled: Flow<Map<Int, Boolean>> = dataStore.data.map { prefs -> enabledMap(prefs) }

    suspend fun currentStates(): Map<Int, Boolean> = enabledMap(dataStore.data.first())

    suspend fun hasPin(profileId: Int): Boolean =
        dataStore.data.first()[hashKey(profileId)].isNullOrBlank().not()

    /** Saves [pin] for [profileId]. Returns false when a PIN exists and [currentPin] does not match it. */
    suspend fun setPin(profileId: Int, pin: String, currentPin: String?): Boolean {
        if (hasPin(profileId)) {
            if (currentPin.isNullOrBlank()) return false
            if (verify(profileId, currentPin) != VerifyResult.Unlocked) return false
        }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val encoded = encode(salt) + ":" + encode(hash(pin, salt))
        dataStore.edit { prefs -> prefs[hashKey(profileId)] = encoded }
        resetAttempts(profileId)
        return true
    }

    /** Removes the PIN after checking [currentPin]. Returns false when it is wrong. */
    suspend fun clearPin(profileId: Int, currentPin: String?): Boolean {
        if (!hasPin(profileId)) return true
        if (currentPin.isNullOrBlank()) return false
        if (verify(profileId, currentPin) != VerifyResult.Unlocked) return false
        removePin(profileId)
        return true
    }

    /** Drops a profile's PIN without checking it (the profile itself was deleted). */
    suspend fun removePin(profileId: Int) {
        dataStore.edit { prefs -> prefs.remove(hashKey(profileId)) }
        resetAttempts(profileId)
    }

    suspend fun verify(profileId: Int, pin: String): VerifyResult {
        val now = System.currentTimeMillis()
        val until = lockedUntilMs[profileId] ?: 0L
        if (until > now) return VerifyResult.TooManyAttempts(((until - now + 999) / 1000).toInt())
        val stored = dataStore.data.first()[hashKey(profileId)]
        if (stored.isNullOrBlank()) return VerifyResult.NoPin
        val parts = stored.split(":")
        val matches = parts.size == 2 && try {
            MessageDigest.isEqual(hash(pin, decode(parts[0])), decode(parts[1]))
        } catch (e: IllegalArgumentException) {
            false
        }
        if (matches) {
            resetAttempts(profileId)
            return VerifyResult.Unlocked
        }
        val failures = (failedAttempts[profileId] ?: 0) + 1
        failedAttempts[profileId] = failures
        if (failures >= MAX_ATTEMPTS) {
            failedAttempts[profileId] = 0
            lockedUntilMs[profileId] = now + LOCKOUT_MS
            return VerifyResult.TooManyAttempts((LOCKOUT_MS / 1000).toInt())
        }
        return VerifyResult.Wrong
    }

    suspend fun clearAll() {
        dataStore.edit { prefs -> prefs.clear() }
        failedAttempts.clear()
        lockedUntilMs.clear()
    }

    private fun resetAttempts(profileId: Int) {
        failedAttempts.remove(profileId)
        lockedUntilMs.remove(profileId)
    }

    private fun enabledMap(prefs: Preferences): Map<Int, Boolean> =
        prefs.asMap().keys.mapNotNull { key ->
            key.name.removePrefix(HASH_PREFIX).takeIf { key.name.startsWith(HASH_PREFIX) }?.toIntOrNull()
        }.filter { id -> !prefs[hashKey(id)].isNullOrBlank() }
            .associateWith { true }

    private fun hashKey(profileId: Int) = stringPreferencesKey("$HASH_PREFIX$profileId")

    private suspend fun hash(pin: String, salt: ByteArray): ByteArray = withContext(Dispatchers.Default) {
        val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
        try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(text: String) = Base64.decode(text, Base64.NO_WRAP)

    private companion object {
        const val HASH_PREFIX = "pin_hash_"
        const val PBKDF2_ITERATIONS = 20_000
        const val MAX_ATTEMPTS = 5
        const val LOCKOUT_MS = 30_000L
    }
}
