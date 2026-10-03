package ru.bsv.candycmd

import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import ru.bsv.candycmd.connection.AndroidDeviceNetwork
import ru.bsv.candycmd.connection.EncryptedProfileStore
import ru.bsv.candycmd.shared.connection.*
import java.io.File
import java.security.KeyStore
import java.util.UUID

class ProfileSecurityTest {
    @Test fun encryptedProfileSurvivesRecreationRejectsTamperingAndDeletesKey() = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(target.cacheDir, "profile-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(target) {
            override fun getNoBackupFilesDir() = directory
        }
        // Separate alias and files ensure tests cannot affect an installed user's profile.
        val alias = "candycmd.test.${UUID.randomUUID()}"
        val store = EncryptedProfileStore(context, alias)
        val profile = DeviceProfile(requireNotNull(LocalAddress.parse(listOf(192, 168, 50, 123).joinToString("."))),
            "AABBCCDDEEFF", "fixture-key-1234".encodeToByteArray())
        val file = File(directory, "device-profile")
        try {
            assertNull(store.load())
            store.save(profile)
            val original = file.readBytes()
            val content = original.toString(Charsets.ISO_8859_1)
            for (secret in listOf(profile.address.value, profile.mac, "fixture-key-1234")) {
                assertFalse(content.contains(requireNotNull(secret)))
                assertFalse(profile.toString().contains(secret))
            }
            val restored = requireNotNull(EncryptedProfileStore(context, alias).load())
            assertEquals(profile.address.value, restored.address.value)
            assertEquals(profile.mac, restored.mac)
            assertArrayEquals(profile.keyBytes(), restored.keyBytes())
            store.save(profile)
            assertFalse(original.contentEquals(file.readBytes()))
            val tampered = file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            file.writeBytes(tampered)
            val error = runCatching { store.load() }.exceptionOrNull()
            assertEquals(ConnectionProblem.STORAGE_UNAVAILABLE, (error as ConnectionException).problem)
            assertArrayEquals(tampered, file.readBytes())
            store.delete()
            assertNull(store.load())
            val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            assertFalse(keystore.containsAlias(alias))
        } finally { store.delete(); directory.deleteRecursively() }
    }

    @Test fun privateStorageIsExcludedFromBackupAndLanPermissionMatchesPlatform() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(0, target.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        assertFalse(target.noBackupFilesDir.canonicalPath.startsWith(target.filesDir.canonicalPath + "/"))
        assertTrue(target.noBackupFilesDir.name == "no_backup")
        val permission = "android.permission.ACCESS_LOCAL_NETWORK"
        val network = AndroidDeviceNetwork(target)
        assertEquals(Build.VERSION.SDK_INT < 37 ||
            target.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED,
            network.hasPermission())
    }
}
