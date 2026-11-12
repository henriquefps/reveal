package dev.hfps.reveal.sync.account

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The server this device is paired with (no secret: the key lives in [SecretStore]). */
data class SyncAccount(
    val url: String,
    val serverId: String,
    val serverName: String,
    val deviceId: String,
    val pairedAt: Long,
)

/** What syncs, chosen on the Sync screen (ADR 0018 "Settings"); edits and library metadata always sync. */
data class SyncOptions(
    /** Make and upload smart previews of photos the server cannot serve. */
    val previews: Boolean = true,
    /** Back up the originals of device-only photos (per folder too: `sources.backup`). */
    val originals: Boolean = true,
    /** Originals only over an unmetered network (Wi-Fi). */
    val originalsWifiOnly: Boolean = true,
)

/** Where the device key is kept: the Android Keystore in the app, memory in tests. */
interface SecretStore {
    fun read(): String?

    fun write(secret: String)

    fun clear()
}

/**
 * The device key, encrypted with an AES-GCM key that never leaves the Android Keystore (ADR 0018 "Security"); the
 * ciphertext is in no-backup preferences, so a device backup never carries it and a restore must pair again.
 */
class KeystoreSecretStore(context: Context) : SecretStore {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun read(): String? {
        val stored = prefs.getString(KEY_CIPHER, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        }.getOrNull()
    }

    override fun write(secret: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        prefs.edit(commit = true) { putString(KEY_CIPHER, Base64.encodeToString(sealed, Base64.NO_WRAP)) }
    }

    override fun clear() {
        prefs.edit(commit = true) { remove(KEY_CIPHER) }
        runCatching { keyStore().deleteEntry(ALIAS) }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun key(): SecretKey {
        (keyStore().getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val FILE = "sync-secret"
        const val KEY_CIPHER = "deviceKey"
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "reveal.sync.deviceKey"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val IV_BYTES = 12
        const val KEY_BITS = 256
    }
}

/** An in-memory [SecretStore] (JVM tests). */
class MemorySecretStore(private var secret: String? = null) : SecretStore {
    override fun read(): String? = secret

    override fun write(secret: String) {
        this.secret = secret
    }

    override fun clear() {
        secret = null
    }
}

/** The paired account, the options and the sync status, in preferences; the key in [secrets]. */
class SyncAccountStore(context: Context, val secrets: SecretStore) {
    private val prefs: SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val _account = MutableStateFlow(readAccount())
    private val _options = MutableStateFlow(readOptions())

    val account: StateFlow<SyncAccount?> = _account.asStateFlow()
    val options: StateFlow<SyncOptions> = _options.asStateFlow()

    /** The device key, or null when not paired (or the Keystore lost it: pair again). */
    fun apiKey(): String? = if (_account.value == null) null else secrets.read()

    fun save(account: SyncAccount, apiKey: String) {
        secrets.write(apiKey)
        prefs.edit(commit = true) {
            putString(KEY_URL, account.url)
            putString(KEY_SERVER_ID, account.serverId)
            putString(KEY_SERVER_NAME, account.serverName)
            putString(KEY_DEVICE_ID, account.deviceId)
            putLong(KEY_PAIRED_AT, account.pairedAt)
        }
        _account.value = readAccount()
    }

    fun updateServerName(name: String) {
        val current = _account.value ?: return
        if (current.serverName == name) return
        prefs.edit { putString(KEY_SERVER_NAME, name) }
        _account.value = current.copy(serverName = name)
    }

    fun clear() {
        secrets.clear()
        prefs.edit(commit = true) {
            listOf(KEY_URL, KEY_SERVER_ID, KEY_SERVER_NAME, KEY_DEVICE_ID, KEY_PAIRED_AT).forEach(::remove)
        }
        _account.value = null
    }

    fun setOptions(options: SyncOptions) {
        prefs.edit {
            putBoolean(KEY_PREVIEWS, options.previews)
            putBoolean(KEY_ORIGINALS, options.originals)
            putBoolean(KEY_WIFI_ONLY, options.originalsWifiOnly)
        }
        _options.value = options
    }

    private fun readAccount(): SyncAccount? {
        val url = prefs.getString(KEY_URL, null) ?: return null
        return SyncAccount(
            url = url,
            serverId = prefs.getString(KEY_SERVER_ID, "").orEmpty(),
            serverName = prefs.getString(KEY_SERVER_NAME, "").orEmpty(),
            deviceId = prefs.getString(KEY_DEVICE_ID, "").orEmpty(),
            pairedAt = prefs.getLong(KEY_PAIRED_AT, 0),
        )
    }

    private fun readOptions() = SyncOptions(
        previews = prefs.getBoolean(KEY_PREVIEWS, true),
        originals = prefs.getBoolean(KEY_ORIGINALS, true),
        originalsWifiOnly = prefs.getBoolean(KEY_WIFI_ONLY, true),
    )

    private companion object {
        const val FILE = "sync"
        const val KEY_URL = "url"
        const val KEY_SERVER_ID = "serverId"
        const val KEY_SERVER_NAME = "serverName"
        const val KEY_DEVICE_ID = "deviceId"
        const val KEY_PAIRED_AT = "pairedAt"
        const val KEY_PREVIEWS = "previews"
        const val KEY_ORIGINALS = "originals"
        const val KEY_WIFI_ONLY = "originalsWifiOnly"
    }
}
