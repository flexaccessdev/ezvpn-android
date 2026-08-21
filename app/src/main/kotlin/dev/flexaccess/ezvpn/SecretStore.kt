package dev.flexaccess.ezvpn

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecretStoreException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Where the app keeps its secrets: the auth-key list, and each profile's own
 * copy of its auth key and relay token. Values are small strings keyed by name.
 * Every method throws [SecretStoreException] on failure — a dropped write here
 * would silently lose keys, so callers report it.
 */
interface SecretStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

/**
 * The Android counterpart of the Keychain: values AES-GCM-encrypted under a
 * key that lives in the hardware-backed AndroidKeyStore (never exportable),
 * stored in a private `SharedPreferences` file. The Jetpack
 * `EncryptedSharedPreferences` did the same and is deprecated, so the few
 * lines it needs live here. Writes are committed synchronously so a failure
 * is observable.
 */
class KeystoreSecretStore(context: Context) : SecretStore {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    override fun get(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        val parts = stored.split(':')
        if (parts.size != 2) throw SecretStoreException("stored secret \"$key\" is malformed")
        return try {
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            throw SecretStoreException("couldn't decrypt secret \"$key\": ${e.message}", e)
        }
    }

    @Synchronized
    override fun put(key: String, value: String) {
        val encoded = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        } catch (e: Exception) {
            throw SecretStoreException("couldn't encrypt secret \"$key\": ${e.message}", e)
        }
        if (!prefs.edit().putString(key, encoded).commit()) {
            throw SecretStoreException("couldn't write secret \"$key\"")
        }
    }

    @Synchronized
    override fun remove(key: String) {
        if (!prefs.edit().remove(key).commit()) {
            throw SecretStoreException("couldn't remove secret \"$key\"")
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS = "ezvpn-secrets"
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "ezvpn-secret-store"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
