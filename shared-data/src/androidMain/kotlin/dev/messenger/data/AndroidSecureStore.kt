package dev.messenger.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import dev.messenger.data.session.SecureStore
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AndroidSecureStore(context: Context) : SecureStore {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "session.vault"))
    private val lock = Mutex()
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    override suspend fun read(): String? = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!file.baseFile.exists()) return@withLock null
            val encoded = file.readFully()
            require(encoded.size >= IV_BYTES + 16)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, encoded.copyOfRange(0, IV_BYTES)))
            cipher.updateAAD(AAD)
            cipher.doFinal(encoded, IV_BYTES, encoded.size - IV_BYTES).decodeToString(throwOnInvalidSequence = true)
        }
    }
    override suspend fun write(value: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD(AAD)
            check(cipher.iv.size == IV_BYTES)
            val encrypted = cipher.iv + cipher.doFinal(value.encodeToByteArray())
            val output = file.startWrite()
            try { output.write(encrypted); file.finishWrite(output) }
            catch (error: Exception) { file.failWrite(output); throw error }
        }
    }
    companion object {
        private const val KEY_ALIAS = "messenger.session.v1"
        private const val IV_BYTES = 12
        private val AAD = "messenger.session.v1".encodeToByteArray()
    }
}
