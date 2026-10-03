package ru.bsv.candycmd.connection

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import ru.bsv.candycmd.shared.connection.*
import java.io.*
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The entire profile is encrypted; both ciphertext and key are excluded from backup. */
class EncryptedProfileStore internal constructor(context: Context, private val keyAlias: String) : ProfileStore {
    constructor(context: Context) : this(context, "candycmd.device-profile.v1")

    private val file = AtomicFile(File(context.noBackupFilesDir, "device-profile"))

    override suspend fun load(): DeviceProfile? = storage {
        val stream = try { file.openRead() } catch (error: FileNotFoundException) {
            if (file.baseFile.exists()) throw error
            return@storage null
        }
        val blob = stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(512)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 4_096)
                output.write(buffer, 0, count)
            }
            require(output.size() >= 29)
            output.toByteArray()
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        DataInputStream(ByteArrayInputStream(cipher.doFinal(blob.copyOfRange(12, blob.size)))).use { input ->
            require(input.readInt() == 1)
            val address = requireNotNull(LocalAddress.parse(input.readUTF()))
            val mac = input.readUTF().ifEmpty { null }
            require(mac == null || mac.matches(Regex("[0-9A-F]{12}")))
            val size = input.readInt()
            require(size == 0 || size == 16)
            val key = if (size == 0) null else ByteArray(size).also { input.readFully(it) }
            require(input.read() == -1)
            DeviceProfile(address, mac, key)
        }
    }

    override suspend fun save(profile: DeviceProfile) = storage {
        val plain = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use {
                it.writeInt(1)
                it.writeUTF(profile.address.value)
                it.writeUTF(profile.mac.orEmpty())
                val bytes = profile.keyBytes()
                it.writeInt(bytes?.size ?: 0)
                if (bytes != null) it.write(bytes)
            }
        }.toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        val encrypted = try { cipher.doFinal(plain) } finally { plain.fill(0) }
        var output: FileOutputStream? = null
        try {
            output = file.startWrite()
            output.write(cipher.iv)
            output.write(encrypted)
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    override suspend fun delete() = storage {
        file.delete()
        require(!file.baseFile.exists())
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(keyAlias)) store.deleteEntry(keyAlias)
    }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        check(create)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
        }.generateKey()
    }

    private suspend fun <T> storage(block: () -> T): T = withContext(Dispatchers.IO) {
        try { block() } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            throw ConnectionException(ConnectionProblem.STORAGE_UNAVAILABLE)
        }
    }
}
